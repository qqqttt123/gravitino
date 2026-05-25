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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJob.State;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestIcebergPurgeJobStore {

  private static final long TIMEOUT_MS = 300000L;

  private static final String DDL =
      "CREATE TABLE IF NOT EXISTS `iceberg_purge_job` ("
          + "`id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,"
          + "`metalake_name` VARCHAR(128) NOT NULL,"
          + "`catalog_name` VARCHAR(128) NOT NULL,"
          + "`namespace` VARCHAR(512) NOT NULL,"
          + "`object_name` VARCHAR(256) NOT NULL,"
          + "`object_type` VARCHAR(16) NOT NULL,"
          + "`metadata_location` VARCHAR(1024) NOT NULL,"
          + "`file_io_impl` VARCHAR(256) NOT NULL,"
          + "`file_io_props` CLOB NOT NULL,"
          + "`state` VARCHAR(16) NOT NULL,"
          + "`attempts` INT(10) NOT NULL DEFAULT 0,"
          + "`max_attempts` INT(10) NOT NULL,"
          + "`last_error` CLOB NULL,"
          + "`heartbeat_at` BIGINT(20) NULL,"
          + "`next_attempt_at` BIGINT(20) NOT NULL,"
          + "`created_at` BIGINT(20) NOT NULL,"
          + "`created_by` VARCHAR(128) NOT NULL,"
          + "`updated_at` BIGINT(20) NOT NULL,"
          + "PRIMARY KEY (`id`))";

  private static BasicDataSource dataSource;
  private static IcebergPurgeJobStore store;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new BasicDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:purge_store_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(DDL);
    }
    store = new IcebergPurgeJobStore(dataSource);
  }

  @AfterAll
  static void tearDown() throws Exception {
    dataSource.close();
  }

  @BeforeEach
  void truncate() throws Exception {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE `iceberg_purge_job`");
    }
  }

  private IcebergPurgeJob sampleJob() {
    return IcebergPurgeJob.builder()
        .metalakeName("ml")
        .catalogName("cat")
        .namespace("db")
        .objectName("t")
        .objectType(IcebergPurgeJob.TABLE)
        .metadataLocation("file:/wh/db/t/metadata/v1.metadata.json")
        .fileIoImpl("org.apache.iceberg.io.ResolvingFileIO")
        .fileIoProps(ImmutableMap.of("s3.endpoint", "http://minio:9000"))
        .maxAttempts(3)
        .createdBy("alice")
        .build();
  }

  @Test
  void testEnqueueThenLoad() {
    long id = store.enqueue(sampleJob());
    assertTrue(id > 0);

    IcebergPurgeJob loaded = store.load(id);
    assertEquals(State.PENDING, loaded.state());
    assertEquals(0, loaded.attempts());
    assertEquals(3, loaded.maxAttempts());
    assertEquals("db", loaded.namespace());
    assertEquals("t", loaded.objectName());
    assertEquals("http://minio:9000", loaded.fileIoProps().get("s3.endpoint"));
    assertNull(loaded.heartbeatAt());
    assertNull(loaded.lastError());
  }

  @Test
  void testClaimIsExclusive() {
    long id = store.enqueue(sampleJob());
    long now = System.currentTimeMillis();
    long staleBefore = now - TIMEOUT_MS;

    List<Long> candidates = store.readCandidateIds(now, staleBefore, 10);
    assertTrue(candidates.contains(id));

    assertTrue(store.claim(id, now, staleBefore), "first claim should win");
    assertFalse(store.claim(id, now, staleBefore), "second claim must lose");

    IcebergPurgeJob claimed = store.load(id);
    assertEquals(State.RUNNING, claimed.state());
    assertEquals(Long.valueOf(now), claimed.heartbeatAt());
  }

  @Test
  void testStaleRunningIsReclaimable() {
    long id = store.enqueue(sampleJob());
    long now = System.currentTimeMillis();
    store.claim(id, now, now - TIMEOUT_MS);

    // A fresh heartbeat is not reclaimable.
    assertFalse(store.readCandidateIds(now, now - TIMEOUT_MS, 10).contains(id));

    // Once the heartbeat ages past the timeout, the row becomes a candidate again.
    long later = now + TIMEOUT_MS + 1;
    assertTrue(store.readCandidateIds(later, later - TIMEOUT_MS, 10).contains(id));
  }

  @Test
  void testMarkSucceeded() {
    long id = store.enqueue(sampleJob());
    long now = System.currentTimeMillis();
    store.claim(id, now, now - TIMEOUT_MS);

    store.markSucceeded(id);
    IcebergPurgeJob done = store.load(id);
    assertEquals(State.SUCCEEDED, done.state());
    assertNull(done.heartbeatAt());
    assertFalse(store.readCandidateIds(now, now - TIMEOUT_MS, 10).contains(id));
  }

  @Test
  void testMarkForRetryBacksOff() {
    long id = store.enqueue(sampleJob());
    long now = System.currentTimeMillis();
    store.claim(id, now, now - TIMEOUT_MS);

    long nextAttemptAt = now + 60000;
    store.markForRetry(id, 1, nextAttemptAt, "transient boom");

    IcebergPurgeJob retried = store.load(id);
    assertEquals(State.PENDING, retried.state());
    assertEquals(1, retried.attempts());
    assertEquals(nextAttemptAt, retried.nextAttemptAt());
    assertEquals("transient boom", retried.lastError());
    assertNull(retried.heartbeatAt());

    // Not yet due.
    assertFalse(store.readCandidateIds(now, now - TIMEOUT_MS, 10).contains(id));
    // Due after the backoff window.
    assertTrue(store.readCandidateIds(nextAttemptAt, nextAttemptAt - TIMEOUT_MS, 10).contains(id));
  }

  @Test
  void testMarkFailed() {
    long id = store.enqueue(sampleJob());
    long now = System.currentTimeMillis();
    store.claim(id, now, now - TIMEOUT_MS);

    store.markFailed(id, 3, "permanent boom");
    IcebergPurgeJob failed = store.load(id);
    assertEquals(State.FAILED, failed.state());
    assertEquals(3, failed.attempts());
    assertEquals("permanent boom", failed.lastError());
    assertFalse(store.readCandidateIds(now, now - TIMEOUT_MS, 10).contains(id));
  }

  @Test
  void testHeartbeatRefreshesTimestamp() {
    long id = store.enqueue(sampleJob());
    long now = System.currentTimeMillis();
    store.claim(id, now, now - TIMEOUT_MS);

    long beat = now + 5000;
    store.heartbeat(java.util.Collections.singletonList(id), beat);
    assertEquals(Long.valueOf(beat), store.load(id).heartbeatAt());
  }
}
