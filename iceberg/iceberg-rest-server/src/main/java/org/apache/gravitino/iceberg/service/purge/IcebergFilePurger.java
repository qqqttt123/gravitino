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

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.util.Tasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes every file reachable from a dropped table's {@link TableMetadata}. Mirrors what {@code
 * CatalogUtil.dropTableData} deletes, but streams the data/delete files per manifest instead of
 * materializing the whole file set, so memory stays bounded on tables that reference millions of
 * files.
 *
 * <p>Deletion is best effort and idempotent: per-file failures are suppressed, and files that are
 * already gone (a re-run after a crash) raise {@link NotFoundException}, which is treated as
 * success. Only a failure to read the table metadata propagates and fails the job.
 */
final class IcebergFilePurger {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergFilePurger.class);

  private IcebergFilePurger() {}

  /**
   * Deletes all files reachable from {@code metadata}.
   *
   * @param io the FileIO to delete through
   * @param metadata the metadata of the dropped table
   * @param deleteExecutor the pool used to parallelize per-file deletes
   * @param perFileRetries the number of retries for a single file delete
   */
  static void purge(
      FileIO io, TableMetadata metadata, ExecutorService deleteExecutor, int perFileRetries) {
    // Collect manifests once (deduped by identity). Even for huge tables the manifest count is
    // bounded, and each ManifestFile is small; the data files they point at are streamed below.
    Set<ManifestFile> manifests = Sets.newLinkedHashSet();
    List<String> manifestLists = Lists.newArrayList();
    for (Snapshot snapshot : metadata.snapshots()) {
      if (snapshot.manifestListLocation() != null) {
        manifestLists.add(snapshot.manifestListLocation());
      }
      try {
        manifests.addAll(snapshot.allManifests(io));
      } catch (NotFoundException e) {
        // Manifest list already deleted by a previous attempt; its manifests/files are gone too.
        LOG.warn("Manifest list {} already deleted, skipping", snapshot.manifestListLocation(), e);
      }
    }

    // 1. Data and delete files, streamed per manifest so the full set is never materialized.
    for (ManifestFile manifest : manifests) {
      try (CloseableIterable<String> paths = ManifestFiles.readPaths(manifest, io)) {
        deleteAll(io, paths, deleteExecutor, perFileRetries);
      } catch (NotFoundException e) {
        LOG.warn("Manifest {} already deleted, skipping its files", manifest.path(), e);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read manifest " + manifest.path(), e);
      }
    }

    // 2. The manifest files themselves, then 3. the manifest lists.
    deleteAll(
        io, Iterables.transform(manifests, ManifestFile::path), deleteExecutor, perFileRetries);
    deleteAll(io, manifestLists, deleteExecutor, perFileRetries);

    // 4. Previous metadata.json files and statistics files.
    List<String> metadataFiles = Lists.newArrayList();
    metadata.previousFiles().forEach(entry -> metadataFiles.add(entry.file()));
    metadata.statisticsFiles().forEach(file -> metadataFiles.add(file.path()));
    metadata.partitionStatisticsFiles().forEach(file -> metadataFiles.add(file.path()));
    deleteAll(io, metadataFiles, deleteExecutor, perFileRetries);

    // 5. The current metadata.json.
    io.deleteFile(metadata.metadataFileLocation());
  }

  private static void deleteAll(
      FileIO io, Iterable<String> files, ExecutorService deleteExecutor, int perFileRetries) {
    Tasks.foreach(files)
        .executeWith(deleteExecutor)
        .retry(perFileRetries)
        .suppressFailureWhenFinished()
        .run(io::deleteFile);
  }
}
