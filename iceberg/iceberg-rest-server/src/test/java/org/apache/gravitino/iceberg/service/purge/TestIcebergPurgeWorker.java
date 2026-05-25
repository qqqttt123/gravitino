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

import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJob.State;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestIcebergPurgeWorker {

  private static final long TIMEOUT_MS = 300000L;
  private static final String HADOOP_FILE_IO = "org.apache.iceberg.hadoop.HadoopFileIO";

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
  private static IcebergPurgeWorker worker;

  @BeforeAll
  static void setUp() throws Exception {
    dataSource = new BasicDataSource();
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUrl("jdbc:h2:mem:purge_worker_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(DDL);
    }
    store = new IcebergPurgeJobStore(dataSource);
    worker = new IcebergPurgeWorker(store, new IcebergConfig(Collections.emptyMap()));
    worker.setHadoopConf(new Configuration());
  }

  @AfterAll
  static void tearDown() throws Exception {
    worker.close();
    dataSource.close();
  }

  @Test
  void testProcessDeletesFilesAndSucceeds(@TempDir Path tempDir) throws Exception {
    Configuration conf = new Configuration();
    HadoopTables tables = new HadoopTables(conf);
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
    String location = tempDir.resolve("tbl").toString();
    Table table = tables.create(schema, location);

    // A real data file that the purge must remove.
    Path dataPath = tempDir.resolve("tbl/data/datafile.parquet");
    Files.createDirectories(dataPath.getParent());
    Files.write(dataPath, new byte[] {1, 2, 3});
    DataFile dataFile =
        DataFiles.builder(table.spec())
            .withPath(dataPath.toUri().toString())
            .withFileSizeInBytes(3)
            .withRecordCount(1)
            .build();
    table.newAppend().appendFile(dataFile).commit();

    String metadataLocation =
        ((HasTableOperations) table).operations().current().metadataFileLocation();

    long id =
        store.enqueue(
            IcebergPurgeJob.builder()
                .metalakeName("ml")
                .catalogName("cat")
                .namespace("db")
                .objectName("tbl")
                .objectType(IcebergPurgeJob.TABLE)
                .metadataLocation(metadataLocation)
                .fileIoImpl(HADOOP_FILE_IO)
                .fileIoProps(ImmutableMap.of())
                .maxAttempts(3)
                .createdBy("alice")
                .build());

    claimAndProcess(id);

    assertEquals(State.SUCCEEDED, store.load(id).state());
    assertFalse(Files.exists(dataPath), "data file should be deleted");
    String metadataPath =
        metadataLocation.startsWith("file:")
            ? URI.create(metadataLocation).getPath()
            : metadataLocation;
    assertFalse(new File(metadataPath).exists(), "metadata.json should be deleted");
  }

  @Test
  void testProcessFailsTerminallyWhenMetadataMissing() {
    long id =
        store.enqueue(
            IcebergPurgeJob.builder()
                .metalakeName("ml")
                .catalogName("cat")
                .namespace("db")
                .objectName("missing")
                .objectType(IcebergPurgeJob.TABLE)
                .metadataLocation("file:/nonexistent/metadata/v1.metadata.json")
                .fileIoImpl(HADOOP_FILE_IO)
                .fileIoProps(ImmutableMap.of())
                .maxAttempts(1)
                .createdBy("alice")
                .build());

    claimAndProcess(id);

    assertEquals(State.FAILED, store.load(id).state());
  }

  private void claimAndProcess(long id) {
    long now = System.currentTimeMillis();
    store.claim(id, now, now - TIMEOUT_MS);
    worker.process(id);
  }
}
