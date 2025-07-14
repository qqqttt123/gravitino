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
package org.apache.gravitino;

public class Relation {
  private final NameIdentifier sourceIdent;
  private final Entity.EntityType sourceType;
  private final NameIdentifier destIdent;
  private final Entity.EntityType destType;

  public Relation(
      NameIdentifier sourceIdent,
      Entity.EntityType sourceType,
      NameIdentifier destIdent,
      Entity.EntityType destType) {
    this.sourceIdent = sourceIdent;
    this.sourceType = sourceType;
    this.destIdent = destIdent;
    this.destType = destType;
  }

  public NameIdentifier getSourceIdent() {
    return sourceIdent;
  }

  public Entity.EntityType getSourceType() {
    return sourceType;
  }

  public NameIdentifier getDestIdent() {
    return destIdent;
  }

  public Entity.EntityType getDestType() {
    return destType;
  }
}
