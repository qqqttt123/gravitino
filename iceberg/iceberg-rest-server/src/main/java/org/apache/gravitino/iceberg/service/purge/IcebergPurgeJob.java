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
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An async hard-deletion (purge) job, mirroring one row of the {@code iceberg_purge_job} table. A
 * job is created when a table is dropped with {@code purgeRequested=true} on the async path; a
 * worker later rebuilds the table's file set from {@link #metadataLocation()} and deletes every
 * reachable file.
 */
public class IcebergPurgeJob {

  /** Lifecycle state of a purge job. */
  public enum State {
    /** Waiting to be claimed: just enqueued, or backing off for a retry. */
    PENDING,
    /** Claimed by a worker and being deleted; the worker keeps {@code heartbeat_at} fresh. */
    RUNNING,
    /** Every reachable file was deleted (or was already gone). */
    SUCCEEDED,
    /** Exhausted {@code max_attempts}; files may be leaked and need out-of-band cleanup. */
    FAILED,
    /** Cancelled by register-as-recovery before the files were deleted. */
    CANCELLED
  }

  /** Object type a job targets. Only {@link #TABLE} is handled in the initial scope. */
  public static final String TABLE = "TABLE";

  private long id;
  private String metalakeName;
  private String catalogName;
  private String namespace;
  private String objectName;
  private String objectType;
  private String metadataLocation;
  private String fileIoImpl;
  private Map<String, String> fileIoProps;
  private State state;
  private int attempts;
  private int maxAttempts;
  @Nullable private String lastError;
  @Nullable private Long heartbeatAt;
  private long nextAttemptAt;
  private long createdAt;
  private String createdBy;
  private long updatedAt;

  private IcebergPurgeJob() {}

  long id() {
    return id;
  }

  String metalakeName() {
    return metalakeName;
  }

  String catalogName() {
    return catalogName;
  }

  String namespace() {
    return namespace;
  }

  String objectName() {
    return objectName;
  }

  String objectType() {
    return objectType;
  }

  String metadataLocation() {
    return metadataLocation;
  }

  String fileIoImpl() {
    return fileIoImpl;
  }

  Map<String, String> fileIoProps() {
    return fileIoProps;
  }

  State state() {
    return state;
  }

  int attempts() {
    return attempts;
  }

  int maxAttempts() {
    return maxAttempts;
  }

  @Nullable
  String lastError() {
    return lastError;
  }

  @Nullable
  Long heartbeatAt() {
    return heartbeatAt;
  }

  long nextAttemptAt() {
    return nextAttemptAt;
  }

  long createdAt() {
    return createdAt;
  }

  String createdBy() {
    return createdBy;
  }

  long updatedAt() {
    return updatedAt;
  }

  /**
   * Creates a new builder.
   *
   * @return a new {@link Builder}
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link IcebergPurgeJob}. */
  public static class Builder {
    private final IcebergPurgeJob job = new IcebergPurgeJob();

    private Builder() {}

    /**
     * @param id the row id
     * @return this builder
     */
    public Builder id(long id) {
      job.id = id;
      return this;
    }

    /**
     * @param metalakeName the metalake name
     * @return this builder
     */
    public Builder metalakeName(String metalakeName) {
      job.metalakeName = metalakeName;
      return this;
    }

    /**
     * @param catalogName the catalog name
     * @return this builder
     */
    public Builder catalogName(String catalogName) {
      job.catalogName = catalogName;
      return this;
    }

    /**
     * @param namespace the table namespace
     * @return this builder
     */
    public Builder namespace(String namespace) {
      job.namespace = namespace;
      return this;
    }

    /**
     * @param objectName the table or view name
     * @return this builder
     */
    public Builder objectName(String objectName) {
      job.objectName = objectName;
      return this;
    }

    /**
     * @param objectType {@code TABLE} or {@code VIEW}
     * @return this builder
     */
    public Builder objectType(String objectType) {
      job.objectType = objectType;
      return this;
    }

    /**
     * @param metadataLocation the metadata.json location used to rebuild the file set
     * @return this builder
     */
    public Builder metadataLocation(String metadataLocation) {
      job.metadataLocation = metadataLocation;
      return this;
    }

    /**
     * @param fileIoImpl the {@code FileIO} implementation class
     * @return this builder
     */
    public Builder fileIoImpl(String fileIoImpl) {
      job.fileIoImpl = fileIoImpl;
      return this;
    }

    /**
     * @param fileIoProps the {@code FileIO} properties captured at enqueue time
     * @return this builder
     */
    public Builder fileIoProps(Map<String, String> fileIoProps) {
      job.fileIoProps = ImmutableMap.copyOf(fileIoProps);
      return this;
    }

    /**
     * @param state the lifecycle state
     * @return this builder
     */
    public Builder state(State state) {
      job.state = state;
      return this;
    }

    /**
     * @param attempts the number of attempts so far
     * @return this builder
     */
    public Builder attempts(int attempts) {
      job.attempts = attempts;
      return this;
    }

    /**
     * @param maxAttempts the number of attempts before the job is marked {@code FAILED}
     * @return this builder
     */
    public Builder maxAttempts(int maxAttempts) {
      job.maxAttempts = maxAttempts;
      return this;
    }

    /**
     * @param lastError the last error message, or {@code null}
     * @return this builder
     */
    public Builder lastError(@Nullable String lastError) {
      job.lastError = lastError;
      return this;
    }

    /**
     * @param heartbeatAt the last heartbeat time in millis, or {@code null} when unclaimed
     * @return this builder
     */
    public Builder heartbeatAt(@Nullable Long heartbeatAt) {
      job.heartbeatAt = heartbeatAt;
      return this;
    }

    /**
     * @param nextAttemptAt the earliest time the job may be claimed, in millis
     * @return this builder
     */
    public Builder nextAttemptAt(long nextAttemptAt) {
      job.nextAttemptAt = nextAttemptAt;
      return this;
    }

    /**
     * @param createdAt the create time in millis
     * @return this builder
     */
    public Builder createdAt(long createdAt) {
      job.createdAt = createdAt;
      return this;
    }

    /**
     * @param createdBy the user who requested the drop
     * @return this builder
     */
    public Builder createdBy(String createdBy) {
      job.createdBy = createdBy;
      return this;
    }

    /**
     * @param updatedAt the last update time in millis
     * @return this builder
     */
    public Builder updatedAt(long updatedAt) {
      job.updatedAt = updatedAt;
      return this;
    }

    /**
     * Builds the {@link IcebergPurgeJob}.
     *
     * @return the built job
     */
    public IcebergPurgeJob build() {
      return job;
    }
  }
}
