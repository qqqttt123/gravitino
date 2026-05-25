/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.iceberg.service.dispatcher;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.authorization.IcebergRESTServerContext;
import org.apache.gravitino.iceberg.service.provider.IcebergConfigProvider;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJob;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJobStore;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestIcebergTableOperationExecutorAsyncPurge {

  private static final String CATALOG = "cat";
  private static final String ASYNC_PURGE_HEADER = "X-Gravitino-Async-Purge";
  private static final TableIdentifier TABLE = TableIdentifier.of(Namespace.of("db"), "t");
  private static final Schema SCHEMA =
      new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));

  private IcebergCatalogWrapperManager wrapperManager;
  private CatalogWrapperForREST wrapper;
  private IcebergPurgeJobStore store;
  private IcebergRequestContext context;
  private Map<String, String> headers;

  @BeforeEach
  void setUp() {
    IcebergConfigProvider configProvider = mock(IcebergConfigProvider.class);
    when(configProvider.getMetalakeName()).thenReturn("ml");
    when(configProvider.getDefaultCatalogName()).thenReturn(CATALOG);
    IcebergRESTServerContext.create(configProvider, false, false, true, null);

    wrapper = mock(CatalogWrapperForREST.class);
    wrapperManager = mock(IcebergCatalogWrapperManager.class);
    when(wrapperManager.getCatalogWrapper(CATALOG)).thenReturn(wrapper);
    when(wrapper.getIcebergConfig()).thenReturn(new IcebergConfig(Collections.emptyMap()));

    store = mock(IcebergPurgeJobStore.class);

    headers = new HashMap<>();
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn(CATALOG);
    when(context.userName()).thenReturn("alice");
    when(context.httpHeaders()).thenReturn(headers);
  }

  @AfterEach
  void tearDown() throws IllegalAccessException {
    Class<?> holder =
        Arrays.stream(IcebergRESTServerContext.class.getDeclaredClasses())
            .filter(c -> c.getSimpleName().equals("InstanceHolder"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("InstanceHolder not found"));
    FieldUtils.writeStaticField(holder, "INSTANCE", null, true);
  }

  private void stubMetadataLocation() {
    LoadTableResponse response = mock(LoadTableResponse.class);
    TableMetadata metadata = mock(TableMetadata.class);
    when(metadata.metadataFileLocation()).thenReturn("file:/wh/db/t/metadata/v1.metadata.json");
    when(response.tableMetadata()).thenReturn(metadata);
    when(wrapper.loadTable(TABLE)).thenReturn(response);
  }

  @Test
  void testAsyncByDefaultEnqueuesOneJob() {
    stubMetadataLocation();
    IcebergTableOperationExecutor executor =
        new IcebergTableOperationExecutor(wrapperManager, store, 5);

    executor.dropTable(context, TABLE, true);

    verify(wrapper).dropTable(TABLE);
    verify(store, times(1)).enqueue(any(IcebergPurgeJob.class));
    verify(wrapper, never()).purgeTable(TABLE);
  }

  @Test
  void testHeaderFalseFallsBackToSync() {
    headers.put(ASYNC_PURGE_HEADER, "false");
    IcebergTableOperationExecutor executor =
        new IcebergTableOperationExecutor(wrapperManager, store, 5);

    executor.dropTable(context, TABLE, true);

    verify(wrapper).purgeTable(TABLE);
    verify(store, never()).enqueue(any(IcebergPurgeJob.class));
    verify(wrapper, never()).dropTable(TABLE);
  }

  @Test
  void testNoStoreFallsBackToSync() {
    IcebergTableOperationExecutor executor = new IcebergTableOperationExecutor(wrapperManager);

    executor.dropTable(context, TABLE, true);

    verify(wrapper).purgeTable(TABLE);
  }

  @Test
  void testCreateTableRejectedWhilePurgeActive() {
    when(store.findActiveJobId(CATALOG, "db", "t")).thenReturn(Optional.of(123L));
    IcebergTableOperationExecutor executor =
        new IcebergTableOperationExecutor(wrapperManager, store, 5);
    CreateTableRequest request =
        CreateTableRequest.builder().withName("t").withSchema(SCHEMA).build();

    assertThrows(
        AlreadyExistsException.class,
        () -> executor.createTable(context, Namespace.of("db"), request));
    verify(wrapper, never()).createTable(any(), any(), anyBoolean());
  }

  @Test
  void testCreateTableProceedsWhenNoActivePurge() {
    when(store.findActiveJobId(CATALOG, "db", "t")).thenReturn(Optional.empty());
    LoadTableResponse created = mock(LoadTableResponse.class);
    when(wrapper.createTable(eq(Namespace.of("db")), any(CreateTableRequest.class), anyBoolean()))
        .thenReturn(created);
    IcebergTableOperationExecutor executor =
        new IcebergTableOperationExecutor(wrapperManager, store, 5);
    CreateTableRequest request =
        CreateTableRequest.builder().withName("t").withSchema(SCHEMA).build();

    executor.createTable(context, Namespace.of("db"), request);
    verify(wrapper)
        .createTable(eq(Namespace.of("db")), any(CreateTableRequest.class), anyBoolean());
  }

  @Test
  void testNonPurgeDropIsMetadataOnly() {
    IcebergTableOperationExecutor executor =
        new IcebergTableOperationExecutor(wrapperManager, store, 5);

    executor.dropTable(context, TABLE, false);

    verify(wrapper).dropTable(TABLE);
    verify(wrapper, never()).purgeTable(TABLE);
    verify(store, never()).enqueue(any(IcebergPurgeJob.class));
  }
}
