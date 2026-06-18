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

import com.google.common.annotations.VisibleForTesting;
import java.io.Closeable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.gravitino.iceberg.common.IcebergConfig;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJob.State;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.io.FileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background worker that drains {@code iceberg_purge_job}. On every tick it claims a bounded batch
 * of due jobs via {@link IcebergPurgeJobStore#claim}, then deletes each table's files on a
 * processing pool. A separate task heartbeats the jobs it owns so that a peer reclaims them only if
 * this worker dies. Any number of workers (one per server replica) can share a single table with no
 * external coordinator.
 */
public class IcebergPurgeWorker implements Closeable {

  private static final Logger LOG = LoggerFactory.getLogger(IcebergPurgeWorker.class);

  private final IcebergPurgeJobStore store;

  // Passed straight to CatalogUtil.loadFileIO as its hadoopConf argument. Null is fine for object
  // stores (S3/GCS/ADLS), the common async-purge case; tests inject a Hadoop Configuration to
  // exercise local-file deletion.
  private Object hadoopConf;

  private final int workerThreads;
  private final long pollIntervalMs;
  private final int batchSize;
  private final long heartbeatTimeoutMs;
  private final long backoffBaseMs;
  private final long backoffMaxMs;
  private final int deleteThreads;
  private final int perFileRetries;

  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(1, namedFactory("iceberg-purge-scheduler"));
  private final ExecutorService processExecutor;
  private final ExecutorService deleteExecutor;

  // Ids currently being processed by this worker; the heartbeat task keeps them alive.
  private final Set<Long> activeIds = ConcurrentHashMap.newKeySet();

  /**
   * Creates a worker reading its tuning from config.
   *
   * @param store the job store
   * @param config the Iceberg REST server config
   */
  public IcebergPurgeWorker(IcebergPurgeJobStore store, IcebergConfig config) {
    this.store = store;
    this.workerThreads = config.get(IcebergConfig.ASYNC_PURGE_WORKER_THREADS);
    this.pollIntervalMs = config.get(IcebergConfig.ASYNC_PURGE_POLL_INTERVAL_MS);
    this.batchSize = config.get(IcebergConfig.ASYNC_PURGE_BATCH_SIZE);
    this.heartbeatTimeoutMs = config.get(IcebergConfig.ASYNC_PURGE_HEARTBEAT_TIMEOUT_MS);
    this.backoffBaseMs = config.get(IcebergConfig.ASYNC_PURGE_BACKOFF_BASE_MS);
    this.backoffMaxMs = config.get(IcebergConfig.ASYNC_PURGE_BACKOFF_MAX_MS);
    this.deleteThreads = config.get(IcebergConfig.ASYNC_PURGE_DELETE_THREADS);
    this.perFileRetries = config.get(IcebergConfig.ASYNC_PURGE_PER_FILE_RETRIES);
    this.processExecutor =
        Executors.newFixedThreadPool(workerThreads, namedFactory("iceberg-purge-worker"));
    this.deleteExecutor =
        Executors.newFixedThreadPool(deleteThreads, namedFactory("iceberg-purge-delete"));
  }

  @VisibleForTesting
  void setHadoopConf(Object hadoopConf) {
    this.hadoopConf = hadoopConf;
  }

  /** Starts the poll and heartbeat schedules. */
  public void start() {
    scheduler.scheduleWithFixedDelay(
        this::pollQuietly, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    long heartbeatIntervalMs = Math.max(1000, heartbeatTimeoutMs / 3);
    scheduler.scheduleWithFixedDelay(
        this::heartbeatQuietly, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
    LOG.info(
        "Iceberg async purge worker started: workerThreads={}, pollIntervalMs={}, batchSize={}",
        workerThreads,
        pollIntervalMs,
        batchSize);
  }

  @VisibleForTesting
  void poll() {
    int free = workerThreads - activeIds.size();
    if (free <= 0) {
      return;
    }
    long now = System.currentTimeMillis();
    long staleBefore = now - heartbeatTimeoutMs;
    List<Long> candidates = store.readCandidateIds(now, staleBefore, Math.min(batchSize, free));
    for (Long id : candidates) {
      if (activeIds.contains(id)) {
        continue;
      }
      if (store.claim(id, now, staleBefore)) {
        activeIds.add(id);
        processExecutor.execute(
            () -> {
              try {
                process(id);
              } finally {
                activeIds.remove(id);
              }
            });
      }
    }
  }

  @VisibleForTesting
  void process(long id) {
    Optional<IcebergPurgeJob> loaded = store.load(id);
    if (!loaded.isPresent() || loaded.get().state() != State.RUNNING) {
      // Cancelled by recovery, already finished, or reclaimed elsewhere; nothing to do.
      return;
    }
    IcebergPurgeJob job = loaded.get();
    FileIO io = null;
    try {
      io = CatalogUtil.loadFileIO(job.fileIoImpl(), job.fileIoProps(), hadoopConf);
      TableMetadata metadata = TableMetadataParser.read(io, job.metadataLocation());
      IcebergFilePurger.purge(io, metadata, deleteExecutor, perFileRetries);
      store.markSucceeded(id);
      LOG.info("Purged {}.{} (job {})", job.namespace(), job.objectName(), id);
    } catch (Exception e) {
      int attempts = job.attempts() + 1;
      if (attempts >= job.maxAttempts()) {
        LOG.error("Purge job {} failed after {} attempts", id, attempts, e);
        store.markFailed(id, attempts, message(e));
      } else {
        long nextAttemptAt = System.currentTimeMillis() + backoff(attempts);
        LOG.warn("Purge job {} failed (attempt {}), will retry", id, attempts, e);
        store.markForRetry(id, attempts, nextAttemptAt, message(e));
      }
    } finally {
      if (io != null) {
        io.close();
      }
    }
  }

  private long backoff(int attempts) {
    long exp = backoffBaseMs * (1L << Math.min(attempts, 20));
    long capped = Math.min(backoffMaxMs, exp);
    long jitter = ThreadLocalRandom.current().nextLong(capped / 10 + 1);
    return capped + jitter;
  }

  private void pollQuietly() {
    try {
      poll();
    } catch (Exception e) {
      LOG.error("Iceberg purge poll tick failed", e);
    }
  }

  private void heartbeatQuietly() {
    try {
      if (!activeIds.isEmpty()) {
        store.heartbeat(activeIds, System.currentTimeMillis());
      }
    } catch (Exception e) {
      LOG.error("Iceberg purge heartbeat failed", e);
    }
  }

  private static String message(Exception e) {
    return e.getClass().getName() + ": " + e.getMessage();
  }

  private static ThreadFactory namedFactory(String prefix) {
    return r -> {
      Thread t = new Thread(r, prefix);
      t.setDaemon(true);
      return t;
    };
  }

  @Override
  public void close() {
    scheduler.shutdownNow();
    processExecutor.shutdown();
    deleteExecutor.shutdown();
    try {
      if (!processExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        processExecutor.shutdownNow();
      }
      if (!deleteExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        deleteExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      processExecutor.shutdownNow();
      deleteExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
