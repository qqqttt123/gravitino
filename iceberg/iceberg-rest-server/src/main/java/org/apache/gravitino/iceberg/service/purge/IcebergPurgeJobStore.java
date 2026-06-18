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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.apache.gravitino.iceberg.service.purge.IcebergPurgeJob.State;

/**
 * JDBC-backed store for {@link IcebergPurgeJob} rows in the {@code iceberg_purge_job} table.
 *
 * <p>Uses plain JDBC with standard SQL so it runs unchanged on MySQL, PostgreSQL and H2. Job
 * claiming is an optimistic compare-and-swap {@code UPDATE} (see {@link #claim}); the {@code
 * affected_rows == 1} check is the serialization point that lets any number of worker replicas
 * share one table without an external coordinator.
 */
public class IcebergPurgeJobStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, String>> PROPS_TYPE =
      new TypeReference<Map<String, String>>() {};

  private static final String COLUMNS =
      "metalake_name, catalog_name, namespace, object_name, object_type, metadata_location, "
          + "file_io_impl, file_io_props, state, attempts, max_attempts, last_error, heartbeat_at, "
          + "next_attempt_at, created_at, created_by, updated_at";

  private static final String INSERT_SQL =
      "INSERT INTO iceberg_purge_job ("
          + COLUMNS
          + ") "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String SELECT_BY_ID_SQL =
      "SELECT id, " + COLUMNS + " FROM iceberg_purge_job WHERE id = ?";

  // A non-terminal job (PENDING or RUNNING) keeps the identifier occupied even though its catalog
  // entry is already dropped; SUCCEEDED / FAILED / CANCELLED no longer block reuse.
  private static final String SELECT_ACTIVE_ID_SQL =
      "SELECT id FROM iceberg_purge_job "
          + "WHERE catalog_name = ? AND namespace = ? AND object_name = ? "
          + "AND state IN ('PENDING', 'RUNNING') ORDER BY id LIMIT 1";

  // A job is claimable when it is PENDING, or RUNNING but its heartbeat has gone stale.
  private static final String CLAIMABLE_PREDICATE =
      "(state = 'PENDING' OR (state = 'RUNNING' "
          + "AND (heartbeat_at IS NULL OR heartbeat_at < ?)))";

  private static final String SELECT_CANDIDATES_SQL =
      "SELECT id FROM iceberg_purge_job WHERE next_attempt_at <= ? AND "
          + CLAIMABLE_PREDICATE
          + " ORDER BY next_attempt_at LIMIT ?";

  private static final String CLAIM_SQL =
      "UPDATE iceberg_purge_job SET state = 'RUNNING', heartbeat_at = ?, updated_at = ? "
          + "WHERE id = ? AND "
          + CLAIMABLE_PREDICATE;

  private static final String MARK_SUCCEEDED_SQL =
      "UPDATE iceberg_purge_job SET state = 'SUCCEEDED', heartbeat_at = NULL, updated_at = ? "
          + "WHERE id = ?";

  private static final String MARK_RETRY_SQL =
      "UPDATE iceberg_purge_job SET state = 'PENDING', attempts = ?, next_attempt_at = ?, "
          + "last_error = ?, heartbeat_at = NULL, updated_at = ? WHERE id = ?";

  private static final String MARK_FAILED_SQL =
      "UPDATE iceberg_purge_job SET state = 'FAILED', attempts = ?, last_error = ?, "
          + "heartbeat_at = NULL, updated_at = ? WHERE id = ?";

  private final DataSource dataSource;

  /**
   * Creates a store over the given data source.
   *
   * @param dataSource the JDBC data source pointing at the Gravitino metastore database
   */
  public IcebergPurgeJobStore(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Persists a new {@code PENDING} job, claimable immediately. The job's {@code maxAttempts} is
   * honored; {@code state}, {@code attempts}, timestamps and {@code next_attempt_at} are set here.
   *
   * @param job the job to enqueue
   * @return the generated row id
   */
  public long enqueue(IcebergPurgeJob job) {
    long now = System.currentTimeMillis();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
      stmt.setString(1, job.metalakeName());
      stmt.setString(2, job.catalogName());
      stmt.setString(3, job.namespace());
      stmt.setString(4, job.objectName());
      stmt.setString(5, job.objectType());
      stmt.setString(6, job.metadataLocation());
      stmt.setString(7, job.fileIoImpl());
      stmt.setString(8, writeProps(job.fileIoProps()));
      stmt.setString(9, State.PENDING.name());
      stmt.setInt(10, 0);
      stmt.setInt(11, job.maxAttempts());
      stmt.setString(12, null);
      stmt.setNull(13, java.sql.Types.BIGINT);
      stmt.setLong(14, now);
      stmt.setLong(15, now);
      stmt.setString(16, job.createdBy());
      stmt.setLong(17, now);
      stmt.executeUpdate();

      try (ResultSet keys = stmt.getGeneratedKeys()) {
        if (keys.next()) {
          return keys.getLong(1);
        }
        throw new GravitinoRuntimeException("No generated key returned for enqueued purge job");
      }
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to enqueue purge job");
    }
  }

  /**
   * Returns the id of an active (PENDING or RUNNING) purge job for the identifier, or {@code null}
   * if none. Used as the name-reuse tombstone: while such a job exists the files are not yet
   * deleted, so a {@code createTable} at the same identifier must be rejected.
   *
   * @param catalogName the catalog name
   * @param namespace the table namespace
   * @param objectName the table name
   * @return the blocking job id, or {@link Optional#empty()} if the identifier is free
   */
  public Optional<Long> findActiveJobId(String catalogName, String namespace, String objectName) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(SELECT_ACTIVE_ID_SQL)) {
      stmt.setString(1, catalogName);
      stmt.setString(2, namespace);
      stmt.setString(3, objectName);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to look up active purge job");
    }
  }

  /**
   * Reads up to {@code batch} claimable job ids, ready for {@link #claim}.
   *
   * @param now the current time in millis
   * @param staleBefore heartbeat timestamps older than this mark a RUNNING job as abandoned
   * @param batch the maximum number of ids to return
   * @return the candidate ids, ordered by {@code next_attempt_at}
   */
  public List<Long> readCandidateIds(long now, long staleBefore, int batch) {
    List<Long> ids = Lists.newArrayList();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(SELECT_CANDIDATES_SQL)) {
      stmt.setLong(1, now);
      stmt.setLong(2, staleBefore);
      stmt.setInt(3, batch);
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getLong(1));
        }
      }
      return ids;
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to read purge job candidates");
    }
  }

  /**
   * Atomically claims a candidate by moving it to {@code RUNNING}. The row is ours only if it is
   * still claimable, which makes concurrent claims across replicas mutually exclusive.
   *
   * @param id the job id
   * @param now the current time in millis, written as the first heartbeat
   * @param staleBefore heartbeat timestamps older than this mark a RUNNING job as abandoned
   * @return true if this caller won the claim
   */
  public boolean claim(long id, long now, long staleBefore) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(CLAIM_SQL)) {
      stmt.setLong(1, now);
      stmt.setLong(2, now);
      stmt.setLong(3, id);
      stmt.setLong(4, staleBefore);
      return stmt.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to claim purge job " + id);
    }
  }

  /**
   * Loads a job by id.
   *
   * @param id the job id
   * @return the job, or {@link Optional#empty()} if no such row exists
   */
  public Optional<IcebergPurgeJob> load(long id) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(SELECT_BY_ID_SQL)) {
      stmt.setLong(1, id);
      try (ResultSet rs = stmt.executeQuery()) {
        return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to load purge job " + id);
    }
  }

  /**
   * Refreshes the heartbeat of the given RUNNING jobs so peers do not reclaim them.
   *
   * @param ids the ids of jobs this worker is processing
   * @param now the current time in millis
   */
  public void heartbeat(Collection<Long> ids, long now) {
    if (ids.isEmpty()) {
      return;
    }
    String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
    String sql =
        "UPDATE iceberg_purge_job SET heartbeat_at = ?, updated_at = ? "
            + "WHERE state = 'RUNNING' AND id IN ("
            + placeholders
            + ")";
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      stmt.setLong(1, now);
      stmt.setLong(2, now);
      int index = 3;
      for (Long id : ids) {
        stmt.setLong(index++, id);
      }
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to heartbeat purge jobs");
    }
  }

  /**
   * Marks a job as successfully completed.
   *
   * @param id the job id
   */
  public void markSucceeded(long id) {
    update(MARK_SUCCEEDED_SQL, "mark succeeded", id, stmt -> stmt.setLong(1, now()));
  }

  /**
   * Returns a transiently-failed job to {@code PENDING} for a later retry.
   *
   * @param id the job id
   * @param attempts the updated attempt count
   * @param nextAttemptAt the earliest time the job may be claimed again, in millis
   * @param error the last error message
   */
  public void markForRetry(long id, int attempts, long nextAttemptAt, String error) {
    update(
        MARK_RETRY_SQL,
        "mark for retry",
        id,
        stmt -> {
          stmt.setInt(1, attempts);
          stmt.setLong(2, nextAttemptAt);
          stmt.setString(3, truncate(error));
          stmt.setLong(4, now());
        });
  }

  /**
   * Marks a job as permanently failed after exhausting its attempts.
   *
   * @param id the job id
   * @param attempts the final attempt count
   * @param error the last error message
   */
  public void markFailed(long id, int attempts, String error) {
    update(
        MARK_FAILED_SQL,
        "mark failed",
        id,
        stmt -> {
          stmt.setInt(1, attempts);
          stmt.setString(2, truncate(error));
          stmt.setLong(3, now());
        });
  }

  private interface ParamSetter {
    void set(PreparedStatement stmt) throws SQLException;
  }

  private void update(String sql, String action, long id, ParamSetter setter) {
    try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
      setter.set(stmt);
      // The id is always the last parameter in the UPDATE statements.
      stmt.setLong(parameterCount(sql), id);
      stmt.executeUpdate();
    } catch (SQLException e) {
      throw new GravitinoRuntimeException(e, "Failed to %s purge job %d", action, id);
    }
  }

  private IcebergPurgeJob fromRow(ResultSet rs) throws SQLException {
    long heartbeat = rs.getLong("heartbeat_at");
    boolean heartbeatNull = rs.wasNull();
    return IcebergPurgeJob.builder()
        .id(rs.getLong("id"))
        .metalakeName(rs.getString("metalake_name"))
        .catalogName(rs.getString("catalog_name"))
        .namespace(rs.getString("namespace"))
        .objectName(rs.getString("object_name"))
        .objectType(rs.getString("object_type"))
        .metadataLocation(rs.getString("metadata_location"))
        .fileIoImpl(rs.getString("file_io_impl"))
        .fileIoProps(readProps(rs.getString("file_io_props")))
        .state(State.valueOf(rs.getString("state")))
        .attempts(rs.getInt("attempts"))
        .maxAttempts(rs.getInt("max_attempts"))
        .lastError(rs.getString("last_error"))
        .heartbeatAt(heartbeatNull ? null : heartbeat)
        .nextAttemptAt(rs.getLong("next_attempt_at"))
        .createdAt(rs.getLong("created_at"))
        .createdBy(rs.getString("created_by"))
        .updatedAt(rs.getLong("updated_at"))
        .build();
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private static int parameterCount(String sql) {
    int count = 0;
    for (int i = 0; i < sql.length(); i++) {
      if (sql.charAt(i) == '?') {
        count++;
      }
    }
    return count;
  }

  private static String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() > 4000 ? error.substring(0, 4000) : error;
  }

  private static String writeProps(Map<String, String> props) {
    try {
      return MAPPER.writeValueAsString(props);
    } catch (Exception e) {
      throw new GravitinoRuntimeException(e, "Failed to serialize FileIO properties");
    }
  }

  private static Map<String, String> readProps(String json) {
    try {
      return MAPPER.readValue(json, PROPS_TYPE);
    } catch (Exception e) {
      throw new GravitinoRuntimeException(e, "Failed to deserialize FileIO properties");
    }
  }
}
