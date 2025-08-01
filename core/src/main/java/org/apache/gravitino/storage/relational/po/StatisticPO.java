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
package org.apache.gravitino.storage.relational.po;

import com.google.common.base.Preconditions;
import java.util.Objects;

public class StatisticPO {
  private Long metalakeId;
  private Long statisticId;

  private String statisticName;

  private String statisticValue;
  private Long metadataObjectId;

  private String metadataObjectType;

  private String auditInfo;

  private Long currentVersion;
  private Long lastVersion;
  private Long deletedAt;

  private StatisticPO() {}

  public static Builder builder() {
    return new Builder();
  }

  public Long getMetalakeId() {
    return metalakeId;
  }

  public Long getStatisticId() {
    return statisticId;
  }

  public Long getMetadataObjectId() {
    return metadataObjectId;
  }

  public String getMetadataObjectType() {
    return metadataObjectType;
  }

  public String getStatisticName() {
    return statisticName;
  }

  public String getStatisticValue() {
    return statisticValue;
  }

  public String getAuditInfo() {
    return auditInfo;
  }

  public Long getCurrentVersion() {
    return currentVersion;
  }

  public Long getLastVersion() {
    return lastVersion;
  }

  public Long getDeletedAt() {
    return deletedAt;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StatisticPO)) {
      return false;
    }
    StatisticPO that = (StatisticPO) o;
    return statisticId.equals(that.statisticId)
        && metadataObjectId.equals(that.metadataObjectId)
        && metalakeId.equals(that.metalakeId)
        && metadataObjectType.equals(that.metadataObjectType)
        && statisticName.equals(that.statisticName)
        && statisticValue.equals(that.statisticValue)
        && auditInfo.equals(that.auditInfo)
        && currentVersion.equals(that.currentVersion)
        && lastVersion.equals(that.lastVersion)
        && deletedAt.equals(that.deletedAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        metalakeId,
        statisticId,
        metadataObjectId,
        metadataObjectType,
        statisticName,
        statisticValue,
        auditInfo,
        currentVersion,
        lastVersion,
        deletedAt);
  }

  public static class Builder {

    private final StatisticPO statisticPO;

    public Builder() {
      this.statisticPO = new StatisticPO();
    }

    public Builder withMetalakeId(Long metalakeId) {
      statisticPO.metalakeId = metalakeId;
      return this;
    }

    public Builder withStatisticId(Long statisticId) {
      statisticPO.statisticId = statisticId;
      return this;
    }

    public Builder withMetadataObjectId(Long objectId) {
      statisticPO.metadataObjectId = objectId;
      return this;
    }

    public Builder withMetadataObjectType(String objectType) {
      statisticPO.metadataObjectType = objectType;
      return this;
    }

    public Builder withStatisticName(String statisticName) {
      statisticPO.statisticName = statisticName;
      return this;
    }

    public Builder withStatisticValue(String value) {
      statisticPO.statisticValue = value;
      return this;
    }

    public Builder withAuditInfo(String auditInfo) {
      statisticPO.auditInfo = auditInfo;
      return this;
    }

    public Builder withCurrentVersion(Long currentVersion) {
      statisticPO.currentVersion = currentVersion;
      return this;
    }

    public Builder withLastVersion(Long lastVersion) {
      statisticPO.lastVersion = lastVersion;
      return this;
    }

    public Builder withDeletedAt(Long deletedAt) {
      statisticPO.deletedAt = deletedAt;
      return this;
    }

    public StatisticPO build() {
      Preconditions.checkArgument(statisticPO.metadataObjectId != null, "`objectId is required");
      Preconditions.checkArgument(
          statisticPO.metadataObjectType != null, "`objectType` is required");
      Preconditions.checkArgument(statisticPO.statisticId != null, "`statisticId` is required");
      Preconditions.checkArgument(statisticPO.statisticName != null, "`statisticName` is required");
      Preconditions.checkArgument(statisticPO.statisticValue != null, "`value` is required");
      Preconditions.checkArgument(statisticPO.auditInfo != null, "`auditInfo` is required");
      Preconditions.checkArgument(statisticPO.metalakeId != null, "`metalakeId` is required");
      Preconditions.checkArgument(statisticPO.deletedAt != null, "`deletedAt` is required");
      Preconditions.checkArgument(statisticPO.lastVersion != null, "`lastVersion` is required");
      Preconditions.checkArgument(
          statisticPO.currentVersion != null, "`currentVersion` is required");
      return statisticPO;
    }
  }
}
