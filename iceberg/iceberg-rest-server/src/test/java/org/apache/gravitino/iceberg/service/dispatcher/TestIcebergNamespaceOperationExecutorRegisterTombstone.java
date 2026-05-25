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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.IcebergCatalogWrapperManager;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJobStore;
import org.apache.gravitino.listener.api.event.IcebergRequestContext;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.rest.requests.RegisterTableRequest;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestIcebergNamespaceOperationExecutorRegisterTombstone {

  private static final String CATALOG = "cat";
  private static final Namespace NAMESPACE = Namespace.of("db");

  private IcebergCatalogWrapperManager wrapperManager;
  private CatalogWrapperForREST wrapper;
  private IcebergPurgeJobStore store;
  private IcebergRequestContext context;
  private RegisterTableRequest request;

  @BeforeEach
  void setUp() {
    wrapper = mock(CatalogWrapperForREST.class);
    wrapperManager = mock(IcebergCatalogWrapperManager.class);
    when(wrapperManager.getCatalogWrapper(CATALOG)).thenReturn(wrapper);
    store = mock(IcebergPurgeJobStore.class);
    context = mock(IcebergRequestContext.class);
    when(context.catalogName()).thenReturn(CATALOG);
    request = mock(RegisterTableRequest.class);
    when(request.name()).thenReturn("t");
  }

  @Test
  void testRegisterRejectedWhilePurgeActive() {
    when(store.findActiveJobId(CATALOG, "db", "t")).thenReturn(Optional.of(7L));
    IcebergNamespaceOperationExecutor executor =
        new IcebergNamespaceOperationExecutor(wrapperManager, store);

    assertThrows(
        AlreadyExistsException.class, () -> executor.registerTable(context, NAMESPACE, request));
    verify(wrapper, never()).registerTable(any(), any(), anyBoolean());
  }

  @Test
  void testRegisterProceedsWhenNoActivePurge() {
    when(store.findActiveJobId(CATALOG, "db", "t")).thenReturn(Optional.empty());
    LoadTableResponse registered = mock(LoadTableResponse.class);
    when(wrapper.registerTable(eq(NAMESPACE), eq(request), anyBoolean())).thenReturn(registered);
    IcebergNamespaceOperationExecutor executor =
        new IcebergNamespaceOperationExecutor(wrapperManager, store);

    executor.registerTable(context, NAMESPACE, request);
    verify(wrapper).registerTable(eq(NAMESPACE), eq(request), anyBoolean());
  }
}
