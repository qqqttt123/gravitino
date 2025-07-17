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
package org.apache.gravitino.dto.requests;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.util.Map;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import org.apache.gravitino.json.JsonUtils;
import org.apache.gravitino.rest.RESTRequest;
import org.apache.gravitino.stats.StatisticValue;

@Getter
@EqualsAndHashCode
@ToString
@Builder
@Jacksonized
/** Represents a request to update statistics. */
public class StatisticsUpdateRequest implements RESTRequest {

  @JsonProperty("updates")
  @JsonSerialize(contentUsing = JsonUtils.StatisticValueSerializer.class)
  @JsonDeserialize(contentUsing = JsonUtils.StatisticValueDeserializer.class)
  Map<String, StatisticValue<?>> updateStatistics;

  /**
   * Creates a new StatisticsUpdateRequest with the specified updates.
   *
   * @param updateStatistics The statistics to update.
   */
  public StatisticsUpdateRequest(Map<String, StatisticValue<?>> updateStatistics) {
    this.updateStatistics = updateStatistics;
  }

  /** Default constructor for deserialization. */
  public StatisticsUpdateRequest() {
    this(null);
  }

  @Override
  public void validate() throws IllegalArgumentException {
    if (updateStatistics == null || updateStatistics.isEmpty()) {
      throw new IllegalArgumentException("Update statistics must not be null or empty");
    }
    updateStatistics.forEach(
        (name, value) -> {
          if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Statistic name must not be null or empty");
          }
          if (value == null) {
            throw new IllegalArgumentException("Statistic value for " + name + " must not be null");
          }
        });
  }
}
