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

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.utils.jdbc.JdbcDataSourceConfig;
import org.apache.gravitino.utils.jdbc.JdbcDataSourceFactory;

/** Builds the pooled {@link BasicDataSource} backing the {@link IcebergPurgeJobStore}. */
public final class IcebergPurgeJobStoreFactory {

  private IcebergPurgeJobStoreFactory() {}

  /**
   * Returns whether async purge is configured (its JDBC URL is set).
   *
   * @param config the Iceberg REST server config
   * @return true if async purge should be enabled
   */
  public static boolean isEnabled(IcebergConfig config) {
    String url = config.get(IcebergConfig.ASYNC_PURGE_JDBC_URL);
    return url != null && !url.trim().isEmpty();
  }

  /**
   * Creates a data source for the purge job table from config.
   *
   * @param config the Iceberg REST server config
   * @return a pooled data source the caller must close on shutdown
   */
  public static BasicDataSource createDataSource(IcebergConfig config) {
    JdbcDataSourceConfig dataSourceConfig =
        new JdbcDataSourceConfig(
            config.get(IcebergConfig.ASYNC_PURGE_JDBC_URL),
            config.get(IcebergConfig.ASYNC_PURGE_JDBC_USER),
            config.get(IcebergConfig.ASYNC_PURGE_JDBC_PASSWORD),
            config.get(IcebergConfig.ASYNC_PURGE_JDBC_DRIVER),
            JdbcDataSourceFactory.DEFAULT_MAX_TOTAL,
            JdbcDataSourceFactory.DEFAULT_MIN_IDLE,
            JdbcDataSourceFactory.DEFAULT_MAX_WAIT_MILLIS,
            JdbcDataSourceFactory.DEFAULT_TEST_ON_BORROW,
            JdbcDataSourceFactory.DEFAULT_VALIDATION_QUERY);
    return JdbcDataSourceFactory.create(dataSourceConfig);
  }
}
