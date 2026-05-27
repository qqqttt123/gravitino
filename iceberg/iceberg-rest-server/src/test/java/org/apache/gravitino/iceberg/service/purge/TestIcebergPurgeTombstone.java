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

package org.apache.gravitino.iceberg.service.purge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Optional;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergNamespaceOperationExecutor;
import org.apache.gravitino.iceberg.service.dispatcher.IcebergTableOperationExecutor;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.ImmutableRegisterTableRequest;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.types.Types.NestedField;
import org.apache.iceberg.types.Types.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestIcebergPurgeTombstone {

  private static final Schema TABLE_SCHEMA =
      new Schema(NestedField.required(1, "test_field", StringType.get()));

  private IcebergPurgeJobStore store;
  private IcebergPurgeManager purgeManager;
  private CatalogWrapperForREST wrapper;
  private IcebergRequestContext context;

  @BeforeAll
  static void setUpClass() {
    PurgeTestBackend.init();
  }

  @BeforeEach
  void setUp() {
    PurgeTestBackend.clear();
    store = new IcebergPurgeJobStore(new RandomIdGenerator());
    purgeManager = new IcebergPurgeManager(store, new IcebergConfig(new HashMap<>()));
    wrapper = mock(CatalogWrapperForREST.class);
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn("cat");
    when(context.userName()).thenReturn("alice");
    when(context.requestCredentialVending()).thenReturn(false);
  }

  @AfterEach
  void tearDown() {
    if (purgeManager != null) {
      purgeManager.close();
    }
  }

  @Test
  void testCreateTableBlockedWhileActiveJob() {
    long id = store.enqueue(TestIcebergPurgeJobStore.sampleJob());
    IcebergTableOperationExecutor executor = newTableExecutor();

    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> executor.createTable(context, Namespace.of("db"), createRequestFor("t")));

    store.claimNext(System.currentTimeMillis(), 300_000L, 10);
    store.markSucceeded(id);
    executor.createTable(context, Namespace.of("db"), createRequestFor("t"));

    verify(wrapper).createTable(any(), any(), anyBoolean());
  }

  @Test
  void testRegisterTableBlockedWhileActiveJob() {
    long id = store.enqueue(TestIcebergPurgeJobStore.sampleJob());
    IcebergNamespaceOperationExecutor executor = newNamespaceExecutor();

    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> executor.registerTable(context, Namespace.of("db"), registerRequestFor("t")));

    store.claimNext(System.currentTimeMillis(), 300_000L, 10);
    store.markSucceeded(id);
    executor.registerTable(context, Namespace.of("db"), registerRequestFor("t"));

    verify(wrapper).registerTable(any(), any(), anyBoolean());
  }

  private IcebergTableOperationExecutor newTableExecutor() {
    return new IcebergTableOperationExecutor(wrapperManager(), Optional.of(purgeManager));
  }

  private IcebergNamespaceOperationExecutor newNamespaceExecutor() {
    return new IcebergNamespaceOperationExecutor(wrapperManager(), Optional.of(purgeManager));
  }

  private IcebergCatalogWrapperManager wrapperManager() {
    IcebergCatalogWrapperManager manager = mock(IcebergCatalogWrapperManager.class);
    when(manager.getCatalogWrapper("cat")).thenReturn(wrapper);
    return manager;
  }

  private static CreateTableRequest createRequestFor(String table) {
    return CreateTableRequest.builder().withName(table).withSchema(TABLE_SCHEMA).build();
  }

  private static RegisterTableRequest registerRequestFor(String table) {
    return ImmutableRegisterTableRequest.builder()
        .name(table)
        .metadataLocation("s3://bucket/db/" + table + "/metadata/00000.json")
        .build();
  }
}
