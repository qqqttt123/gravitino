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

import javax.sql.DataSource;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the {@link DataSource} backing the {@link IcebergPurgeJobStore}. The {@code
 * iceberg_purge_job} table lives in Gravitino's relational metastore, so this reuses the entity
 * store's existing connection pool rather than opening a second one. Async purge is therefore
 * available exactly when the relational JDBC backend is initialized (e.g. the Iceberg REST server
 * running embedded in Gravitino), and silently disabled otherwise (e.g. standalone with no
 * metastore).
 */
public final class IcebergPurgeJobStoreFactory {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergPurgeJobStoreFactory.class);

  private IcebergPurgeJobStoreFactory() {}

  /**
   * Returns whether async purge can run, i.e. the relational metastore DataSource is available.
   *
   * @return true if async purge should be enabled
   */
  public static boolean isEnabled() {
    return sharedDataSource() != null;
  }

  /**
   * Returns the entity store's shared relational DataSource, or {@code null} when no relational
   * backend is initialized. The returned DataSource is owned by the entity store; callers must not
   * close it.
   *
   * @return the shared DataSource, or {@code null}
   */
  public static DataSource sharedDataSource() {
    Config config = GravitinoEnv.getInstance().config();
    if (config == null
        || !Configs.RELATIONAL_ENTITY_STORE.equals(config.get(Configs.ENTITY_STORE))) {
      return null;
    }
    try {
      return SqlSessionFactoryHelper.getInstance()
          .getSqlSessionFactory()
          .getConfiguration()
          .getEnvironment()
          .getDataSource();
    } catch (RuntimeException e) {
      // The relational backend is not initialized (e.g. standalone Iceberg REST server).
      LOG.info("Relational metastore not available, async Iceberg purge disabled");
      return null;
    }
  }
}
