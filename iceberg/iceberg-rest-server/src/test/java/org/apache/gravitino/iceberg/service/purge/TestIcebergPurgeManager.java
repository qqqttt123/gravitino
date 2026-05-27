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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.storage.RandomIdGenerator;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.inmemory.InMemoryCatalog;
import org.apache.iceberg.inmemory.InMemoryFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.SupportsBulkOperations;
import org.apache.iceberg.types.Types;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestIcebergPurgeManager {

  private IcebergPurgeJobStore store;

  @BeforeAll
  static void setUpClass() {
    PurgeTestBackend.init();
  }

  @BeforeEach
  void setUp() {
    PurgeTestBackend.clear();
    store = new IcebergPurgeJobStore(new RandomIdGenerator());
  }

  private static IcebergConfig fastPollConfig() {
    Map<String, String> config = new HashMap<>();
    config.put("async-purge.worker-threads", "1");
    config.put("async-purge.poll-interval-secs", "1");
    return new IcebergConfig(config);
  }

  private static IcebergPurgeJob sampleJob() {
    return new IcebergPurgeJob(
        0L,
        "ml",
        "cat",
        "db",
        "t",
        "s3://b/db/t/metadata/0.json",
        NoopFileIO.class.getName(),
        ImmutableMap.of(),
        "alice");
  }

  @Test
  void testDeleteAllBatchesEveryFile() {
    CopyOnWriteArrayList<String> deleted = new CopyOnWriteArrayList<>();
    IcebergPurgeManager svc = new IcebergPurgeManager(store, new IcebergConfig(new HashMap<>()));
    try {
      svc.deleteAll(new RecordingFileIO(deleted), Arrays.asList("a", "b", "c", "d", "e", "f", "g"));
      Assertions.assertEquals(7, deleted.size());
    } finally {
      svc.close();
    }
  }

  @Test
  void testDeleteAllIgnoresAlreadyDeletedFiles() {
    IcebergPurgeManager svc = new IcebergPurgeManager(store, new IcebergConfig(new HashMap<>()));
    try {
      Assertions.assertDoesNotThrow(
          () -> svc.deleteAll(new MissingFileIO(), Arrays.asList("already-gone")));
    } finally {
      svc.close();
    }
  }

  @Test
  void testPurgeFilesDeletesAllReachableFiles() throws Exception {
    InMemoryCatalog catalog = new InMemoryCatalog();
    catalog.initialize("test", ImmutableMap.of());
    Namespace namespace = Namespace.of("db");
    catalog.createNamespace(namespace);
    TableIdentifier identifier = TableIdentifier.of(namespace, "t");
    Schema schema = new Schema(Types.NestedField.required(1, "id", Types.IntegerType.get()));
    Table table = catalog.createTable(identifier, schema);

    // Append a data file so the snapshot has a manifest list, a manifest, and a data file path.
    DataFile dataFile =
        DataFiles.builder(PartitionSpec.unpartitioned())
            .withPath("memory://db/t/data/00000-0.parquet")
            .withFileSizeInBytes(10L)
            .withRecordCount(1L)
            .build();
    table.newAppend().appendFile(dataFile).commit();

    BaseTable base = (BaseTable) catalog.loadTable(identifier);
    FileIO io = base.io();
    String metadataLocation = base.operations().current().metadataFileLocation();
    // Materialize the data file so its deletion is observable in the in-memory FileIO.
    ((InMemoryFileIO) io).addFile(dataFile.location(), new byte[] {1});

    // Capture every reachable file before purge; the manifest list/manifests cannot be read back
    // afterwards because they will have been deleted.
    List<String> expected = new ArrayList<>();
    expected.add(metadataLocation);
    expected.add(dataFile.location());
    for (Snapshot snapshot : base.snapshots()) {
      expected.add(snapshot.manifestListLocation());
      for (ManifestFile manifest : snapshot.allManifests(io)) {
        expected.add(manifest.path());
      }
    }
    expected.forEach(file -> Assertions.assertTrue(((InMemoryFileIO) io).fileExists(file), file));

    IcebergPurgeManager svc = new IcebergPurgeManager(store, new IcebergConfig(new HashMap<>()));
    try {
      svc.purgeFiles(io, metadataLocation);
    } finally {
      svc.close();
    }

    expected.forEach(file -> Assertions.assertFalse(((InMemoryFileIO) io).fileExists(file), file));
  }

  @Test
  void testWorkerRunsJobToSucceeded() {
    AtomicInteger calls = new AtomicInteger();
    IcebergPurgeManager svc =
        new IcebergPurgeManager(store, fastPollConfig()) {
          @Override
          void purgeFiles(FileIO io, String metadataLocation) {
            calls.incrementAndGet();
          }
        };
    long id = store.enqueue(sampleJob());
    svc.start();
    try {
      Awaitility.await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> store.stateOf(id) == IcebergPurgeJob.State.SUCCEEDED);
      Assertions.assertEquals(1, calls.get());
    } finally {
      svc.close();
    }
  }

  @Test
  void testTransientFailureRetriesThenFails() {
    Map<String, String> config = new HashMap<>();
    config.put("async-purge.worker-threads", "1");
    config.put("async-purge.poll-interval-secs", "1");
    config.put("async-purge.max-attempts", "3");
    IcebergPurgeManager svc =
        new IcebergPurgeManager(store, new IcebergConfig(config)) {
          @Override
          void purgeFiles(FileIO io, String metadataLocation) {
            throw new RuntimeException("transient");
          }
        };
    long id = store.enqueue(sampleJob());
    svc.start();
    try {
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> store.stateOf(id) == IcebergPurgeJob.State.FAILED);
    } finally {
      svc.close();
    }
  }

  @Test
  void testIsNameOccupiedDelegatesToStore() {
    IcebergPurgeManager svc = new IcebergPurgeManager(store, new IcebergConfig(new HashMap<>()));
    try {
      Assertions.assertFalse(svc.isNameOccupied("cat", "db", "t"));
      svc.enqueue(sampleJob());
      Assertions.assertTrue(svc.isNameOccupied("cat", "db", "t"));
    } finally {
      svc.close();
    }
  }

  static class RecordingFileIO implements SupportsBulkOperations {
    private final CopyOnWriteArrayList<String> deleted;

    RecordingFileIO(CopyOnWriteArrayList<String> deleted) {
      this.deleted = deleted;
    }

    @Override
    public InputFile newInputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputFile newOutputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String path) {
      deleted.add(path);
    }

    @Override
    public void deleteFiles(Iterable<String> paths) {
      for (String path : paths) {
        deleted.add(path);
      }
    }
  }

  public static class NoopFileIO implements FileIO {
    @Override
    public InputFile newInputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputFile newOutputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String path) {
      throw new UnsupportedOperationException();
    }
  }

  private static class MissingFileIO implements FileIO {
    @Override
    public InputFile newInputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public OutputFile newOutputFile(String path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteFile(String path) {
      throw new NotFoundException("Missing file: %s", path);
    }
  }
}
