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

import com.google.common.collect.ImmutableMap;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.gravitino.storage.relational.JDBCBackend;
import org.apache.gravitino.storage.relational.RelationalBackend;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;

/**
 * Shared purge-store test logic exercised against a real entity-store relational backend. Concrete
 * subclasses supply the backend (MySQL, PostgreSQL, ...) by mirroring the core module's {@code
 * TestJDBCBackend}: each points the shared {@link SqlSessionFactoryHelper} at its database (whose
 * schema, including {@code iceberg_cleanup_job}, is applied by the database init helper), so the
 * mapper SQL is verified across backends.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractIcebergPurgeJobStoreBackendTest {

  private RelationalBackend backend;
  private IcebergPurgeJobStore store;

  /** Returns the JDBC connection settings for this backend, with its schema already applied. */
  protected abstract JdbcConfig jdbcConfig() throws Exception;

  @BeforeAll
  void setUp() throws Exception {
    JdbcConfig jdbc = jdbcConfig();
    Config config = Mockito.mock(Config.class);
    Mockito.when(config.get(Configs.ENTITY_STORE)).thenReturn(Configs.RELATIONAL_ENTITY_STORE);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_STORE))
        .thenReturn(Configs.DEFAULT_ENTITY_RELATIONAL_STORE);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_URL)).thenReturn(jdbc.url);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_USER)).thenReturn(jdbc.user);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_PASSWORD))
        .thenReturn(jdbc.password);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_DRIVER)).thenReturn(jdbc.driver);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS))
        .thenReturn(Configs.DEFAULT_RELATIONAL_JDBC_BACKEND_MAX_CONNECTIONS);
    Mockito.when(config.get(Configs.ENTITY_RELATIONAL_JDBC_BACKEND_WAIT_MILLISECONDS))
        .thenReturn(Configs.DEFAULT_RELATIONAL_JDBC_BACKEND_MAX_WAIT_MILLISECONDS);

    // Reset any factory a previous test class initialized, then point it at this backend.
    SqlSessionFactoryHelper.getInstance().close();
    backend = new JDBCBackend();
    backend.initialize(config);
    afterBackendInitialized();
    store = new IcebergPurgeJobStore(new RandomIdGenerator());
  }

  @AfterAll
  void tearDown() throws Exception {
    if (backend != null) {
      backend.close();
    }
    if (ContainerSuite.initialized()) {
      ContainerSuite.getInstance().close();
    }
  }

  @BeforeEach
  void clear() {
    execute("DELETE FROM iceberg_cleanup_job");
  }

  protected void afterBackendInitialized() {}

  protected void executeStatements(String sqlStatements) {
    Arrays.stream(sqlStatements.split(";"))
        .map(String::trim)
        .filter(statement -> !statement.isEmpty())
        .forEach(this::execute);
  }

  protected void execute(String sql) {
    try (SqlSession session =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = session.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (Exception e) {
      throw new RuntimeException("Failed to execute purge backend test SQL: " + sql, e);
    }
  }

  private static IcebergPurgeJob sampleJob() {
    return new IcebergPurgeJob(
        0L,
        "ml",
        "cat",
        "db",
        "t",
        "s3://b/db/t/metadata/0.json",
        "org.apache.iceberg.aws.s3.S3FileIO",
        ImmutableMap.of("k", "v"),
        "alice");
  }

  @Test
  void testEnqueueClaimSucceedLifecycle() {
    long id = store.enqueue(sampleJob());
    Assertions.assertTrue(id > 0);
    Assertions.assertTrue(store.hasActiveJob("cat", "db", "t"));

    long now = System.currentTimeMillis();
    IcebergPurgeJob claimed = store.claimNext(now, 300_000L, 10);
    Assertions.assertNotNull(claimed);
    Assertions.assertEquals(id, claimed.id());
    Assertions.assertEquals(ImmutableMap.of("k", "v"), claimed.fileIoProperties());
    Assertions.assertEquals(IcebergPurgeJob.State.RUNNING, store.stateOf(id));
    Assertions.assertNull(store.claimNext(now, 300_000L, 10));

    store.markSucceeded(id);
    Assertions.assertEquals(IcebergPurgeJob.State.SUCCEEDED, store.stateOf(id));
    Assertions.assertFalse(store.hasActiveJob("cat", "db", "t"));
    Assertions.assertEquals(1, store.pruneTerminalBefore(System.currentTimeMillis() + 1));
  }

  @Test
  void testTransientFailureRetriesThenFailsAtCeiling() {
    long id = store.enqueue(sampleJob());
    for (int i = 0; i < 2; i++) {
      store.claimNext(System.currentTimeMillis(), 300_000L, 10);
      store.recordFailure(id, "boom " + i, 3);
      Assertions.assertEquals(IcebergPurgeJob.State.PENDING, store.stateOf(id));
    }
    store.claimNext(System.currentTimeMillis(), 300_000L, 10);
    store.recordFailure(id, "boom final", 3);
    Assertions.assertEquals(IcebergPurgeJob.State.FAILED, store.stateOf(id));
  }

  @Test
  void testHeartbeatCasAndStaleReclaim() {
    long id = store.enqueue(sampleJob());
    long t0 = System.currentTimeMillis();
    store.claimNext(t0, 300_000L, 10);
    Assertions.assertTrue(store.heartbeat(id, t0, t0 + 1000));
    Assertions.assertFalse(store.heartbeat(id, t0, t0 + 2000));
    // A stale RUNNING job is reclaimable once its heartbeat ages past the timeout.
    Assertions.assertEquals(id, store.claimNext(t0 + 400_000L, 300_000L, 10).id());
  }

  /** Minimal JDBC connection settings for a backend whose schema is already initialized. */
  protected static class JdbcConfig {
    final String url;
    final String user;
    final String password;
    final String driver;

    JdbcConfig(String url, String user, String password, String driver) {
      this.url = url;
      this.user = user;
      this.password = password;
      this.driver = driver;
    }
  }
}

class TestIcebergPurgeJobStoreH2Backend extends AbstractIcebergPurgeJobStoreBackendTest {

  @Override
  protected JdbcConfig jdbcConfig() {
    String name = UUID.randomUUID().toString().replace("-", "");
    return new JdbcConfig(
        "jdbc:h2:mem:iceberg_purge_" + name + ";DB_CLOSE_DELAY=-1;MODE=MYSQL",
        "sa",
        "",
        "org.h2.Driver");
  }

  @Override
  protected void afterBackendInitialized() {
    executeStatements(PurgeTestSchema.H2_CREATE);
  }
}

@EnabledIfEnvironmentVariable(named = "dockerTest", matches = "true")
class TestIcebergPurgeJobStoreMySQLBackend extends AbstractIcebergPurgeJobStoreBackendTest {

  private final BaseIT baseIT = new BaseIT();

  @Override
  protected JdbcConfig jdbcConfig() {
    return new JdbcConfig(
        baseIT.startAndInitMySQLBackend(), "root", "root", "com.mysql.cj.jdbc.Driver");
  }
}

@EnabledIfEnvironmentVariable(named = "dockerTest", matches = "true")
class TestIcebergPurgeJobStorePostgreSQLBackend extends AbstractIcebergPurgeJobStoreBackendTest {

  private final BaseIT baseIT = new BaseIT();

  @Override
  protected JdbcConfig jdbcConfig() {
    return new JdbcConfig(baseIT.startAndInitPGBackend(), "root", "root", "org.postgresql.Driver");
  }
}
