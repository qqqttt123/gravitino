/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.iceberg.service.dispatcher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.catalog.lakehouse.iceberg.IcebergConstants;
import org.apache.gravitino.credential.CredentialPrivilege;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper;
import org.apache.gravitino.iceberg.common.utils.IcebergIdentifierUtils;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJob;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJobStore;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.server.authorization.MetadataAuthzHelper;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionConstants;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.PlanTableScanRequest;
import org.apache.iceberg.rest.requests.RenameTableRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ListTablesResponse;
import org.apache.iceberg.rest.responses.LoadCredentialsResponse;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.rest.responses.PlanTableScanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IcebergTableOperationExecutor implements IcebergTableOperationDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergTableOperationExecutor.class);

  private static final String ASYNC_PURGE_HEADER = "X-Gravitino-Async-Purge";
  private static final String DEFAULT_FILE_IO_IMPL = "org.apache.iceberg.io.ResolvingFileIO";

  private final IcebergCatalogWrapperManager icebergCatalogWrapperManager;
  @Nullable private final IcebergPurgeJobStore purgeJobStore;
  private final int purgeMaxAttempts;

  public IcebergTableOperationExecutor(IcebergCatalogWrapperManager icebergCatalogWrapperManager) {
    this(icebergCatalogWrapperManager, null, 0);
  }

  /**
   * Creates an executor wired for async hard deletion.
   *
   * @param icebergCatalogWrapperManager the catalog wrapper manager
   * @param purgeJobStore the purge job store, or {@code null} to always purge synchronously
   * @param purgeMaxAttempts the {@code max_attempts} stamped onto enqueued purge jobs
   */
  public IcebergTableOperationExecutor(
      IcebergCatalogWrapperManager icebergCatalogWrapperManager,
      @Nullable IcebergPurgeJobStore purgeJobStore,
      int purgeMaxAttempts) {
    this.icebergCatalogWrapperManager = icebergCatalogWrapperManager;
    this.purgeJobStore = purgeJobStore;
    this.purgeMaxAttempts = purgeMaxAttempts;
  }

  @Override
  public LoadTableResponse createTable(
      IcebergRequestContext context, Namespace namespace, CreateTableRequest createTableRequest) {
    // Name-reuse tombstone (design §5.13): while a purge job for this identifier is still active,
    // its files are not yet deleted, so recreating the same table is rejected with 409 Conflict.
    if (purgeJobStore != null) {
      Long jobId =
          purgeJobStore.findActiveJobId(
              context.catalogName(), namespace.toString(), createTableRequest.name());
      if (jobId != null) {
        throw new AlreadyExistsException(
            "Cannot create table %s.%s: it is being purged (cleanup job %d still in progress)",
            namespace, createTableRequest.name(), jobId);
      }
    }

    String authenticatedUser = context.userName();
    if (!AuthConstants.ANONYMOUS_USER.equals(authenticatedUser)) {
      String existingOwner = createTableRequest.properties().get(IcebergConstants.OWNER);

      // Override the owner as the authenticated user if different from authenticated user
      if (!authenticatedUser.equals(existingOwner)) {
        Map<String, String> properties = new HashMap<>(createTableRequest.properties());
        properties.put(IcebergConstants.OWNER, authenticatedUser);
        LOG.debug(
            "Overriding table owner from '{}' to authenticated user: '{}'",
            existingOwner,
            authenticatedUser);

        // CreateTableRequest is immutable, so we need to rebuild it with modified properties
        CreateTableRequest.Builder builder =
            CreateTableRequest.builder()
                .withName(createTableRequest.name())
                .withSchema(createTableRequest.schema())
                .withPartitionSpec(createTableRequest.spec())
                .withWriteOrder(createTableRequest.writeOrder())
                .withLocation(createTableRequest.location())
                .setProperties(properties);

        // Preserve the stageCreate flag when rebuilding the request
        if (createTableRequest.stageCreate()) {
          builder.stageCreate();
        }

        createTableRequest = builder.build();
      }
    }

    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .createTable(namespace, createTableRequest, context.requestCredentialVending());
  }

  @Override
  public LoadTableResponse updateTable(
      IcebergRequestContext context,
      TableIdentifier tableIdentifier,
      UpdateTableRequest updateTableRequest) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .updateTable(tableIdentifier, updateTableRequest);
  }

  @Override
  public void dropTable(
      IcebergRequestContext context, TableIdentifier tableIdentifier, boolean purgeRequested) {
    IcebergCatalogWrapper wrapper =
        icebergCatalogWrapperManager.getCatalogWrapper(context.catalogName());
    if (!purgeRequested) {
      wrapper.dropTable(tableIdentifier);
      return;
    }

    if (purgeJobStore == null || !asyncPurgeRequested(context)) {
      // Synchronous fallback: delete files on the request thread, today's behavior.
      wrapper.purgeTable(tableIdentifier);
      return;
    }

    // Async path: capture the metadata location, drop the catalog entry, then enqueue a job that
    // exists only for a table already gone from the catalog.
    TableMetadata metadata = wrapper.loadTable(tableIdentifier).tableMetadata();
    String metadataLocation = metadata.metadataFileLocation();
    IcebergConfig catalogConfig = wrapper.getIcebergConfig();
    String fileIoImpl = catalogConfig.get(IcebergConfig.IO_IMPL);
    if (fileIoImpl == null) {
      fileIoImpl = DEFAULT_FILE_IO_IMPL;
    }

    wrapper.dropTable(tableIdentifier);
    purgeJobStore.enqueue(
        IcebergPurgeJob.builder()
            .metalakeName(IcebergRESTServerContext.getInstance().metalakeName())
            .catalogName(context.catalogName())
            .namespace(tableIdentifier.namespace().toString())
            .objectName(tableIdentifier.name())
            .objectType(IcebergPurgeJob.TABLE)
            .metadataLocation(metadataLocation)
            .fileIoImpl(fileIoImpl)
            .fileIoProps(catalogConfig.getIcebergCatalogProperties())
            .maxAttempts(purgeMaxAttempts)
            .createdBy(context.userName())
            .build());
    LOG.info("Enqueued async purge job for table {}", tableIdentifier);
  }

  // Async is the default; a client opts into synchronous deletion with the
  // `X-Gravitino-Async-Purge: false` request header (a Gravitino extension, not part of the
  // Iceberg REST spec).
  private boolean asyncPurgeRequested(IcebergRequestContext context) {
    for (Map.Entry<String, String> header : context.httpHeaders().entrySet()) {
      if (ASYNC_PURGE_HEADER.equalsIgnoreCase(header.getKey())) {
        return !"false".equalsIgnoreCase(header.getValue());
      }
    }
    return true;
  }

  @Override
  public LoadTableResponse loadTable(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    CredentialPrivilege privilege = CredentialPrivilege.READ;
    if (context.requestCredentialVending()) {
      privilege = getCredentialPrivilege(context, tableIdentifier);
    }

    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .loadTable(tableIdentifier, context.requestCredentialVending(), privilege);
  }

  @Override
  public ListTablesResponse listTable(IcebergRequestContext context, Namespace namespace) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .listTable(namespace);
  }

  @Override
  public boolean tableExists(IcebergRequestContext context, TableIdentifier tableIdentifier) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .tableExists(tableIdentifier);
  }

  @Override
  public void renameTable(IcebergRequestContext context, RenameTableRequest renameTableRequest) {
    icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .renameTable(renameTableRequest);
  }

  @Override
  public LoadCredentialsResponse getTableCredentials(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    CredentialPrivilege privilege = getCredentialPrivilege(context, tableIdentifier);
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .getTableCredentials(tableIdentifier, privilege);
  }

  private static CredentialPrivilege getCredentialPrivilege(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    String metalake = IcebergRESTServerContext.getInstance().metalakeName();
    NameIdentifier identifier =
        IcebergIdentifierUtils.toGravitinoTableIdentifier(
            metalake, context.catalogName(), tableIdentifier);
    boolean writable =
        MetadataAuthzHelper.checkAccess(
            identifier,
            Entity.EntityType.TABLE,
            AuthorizationExpressionConstants.FILTER_MODIFY_TABLE_AUTHORIZATION_EXPRESSION);

    return writable ? CredentialPrivilege.WRITE : CredentialPrivilege.READ;
  }

  @Override
  public PlanTableScanResponse planTableScan(
      IcebergRequestContext context,
      TableIdentifier tableIdentifier,
      PlanTableScanRequest scanRequest) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .planTableScan(tableIdentifier, scanRequest);
  }

  @Override
  public Optional<String> getTableMetadataLocation(
      IcebergRequestContext context, TableIdentifier tableIdentifier) {
    return icebergCatalogWrapperManager
        .getCatalogWrapper(context.catalogName())
        .getTableMetadataLocation(tableIdentifier);
  }
}
