/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.server.authorization;

import java.io.IOException;
import java.security.Principal;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.authorization.Privilege;

/**
 * The default implementation of GravitinoAuthorizer, indicating that metadata permission control is
 * not enabled.
 */
public class PassThroughAuthorizer implements GravitinoAuthorizer {

  @Override
  public void initialize() {}

  @Override
  public boolean authorize(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      Privilege.Name privilege) {
    return true;
  }

  @Override
  public boolean deny(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      Privilege.Name privilege) {
    return false;
  }

  @Override
  public boolean isOwner(Principal principal, String metalake, MetadataObject metadataObject) {
    return true;
  }

  @Override
  public boolean isServiceAdmin() {
    return true;
  }

  @Override
  public boolean isSelf(Entity.EntityType type, NameIdentifier nameIdentifier) {
    return true;
  }

  @Override
  public boolean isMetalakeUser(String metalake) {
    return true;
  }

  @Override
  public boolean hasSetOwnerPermission(String metalake, String type, String fullName) {
    return true;
  }

  @Override
  public boolean hasMetadataPrivilegePermission(String metalake, String type, String fullName) {
    return true;
  }

  @Override
  public void handleRolePrivilegeChange(Long roleId) {}

  @Override
  public void handleRolePrivilegeChange(String metalake, String roleName) {}

  @Override
  public void handleMetadataOwnerChange(
      String metalake, Long oldOwnerId, NameIdentifier nameIdentifier, Entity.EntityType type) {}

  @Override
  public void close() throws IOException {}
}
