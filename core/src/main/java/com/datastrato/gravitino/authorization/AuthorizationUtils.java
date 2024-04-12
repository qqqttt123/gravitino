/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.Entity;
import com.datastrato.gravitino.EntityStore;
import com.datastrato.gravitino.GravitinoEnv;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.catalog.CatalogManager;
import com.datastrato.gravitino.exceptions.ForbiddenException;
import com.datastrato.gravitino.exceptions.NoSuchMetalakeException;
import com.datastrato.gravitino.metalake.MetalakeManager;
import com.datastrato.gravitino.utils.Executable;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.datastrato.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* The utilization class of authorization module*/
public class AuthorizationUtils {

  static final String USER_DOES_NOT_EXIST_MSG = "User %s does not exist in th metalake %s";
  static final String GROUP_DOES_NOT_EXIST_MSG = "Group %s does not exist in th metalake %s";
  static final String ROLE_DOES_NOT_EXIST_MSG = "Role %s does not exist in th metalake %s";
  private static final Logger LOG = LoggerFactory.getLogger(AuthorizationUtils.class);
  private static final String METALAKE_DOES_NOT_EXIST_MSG = "Metalake %s does not exist";
  private static final Object accessControlLock = new Object();

  private AuthorizationUtils() {}

  static void checkMetalakeExists(EntityStore store, String metalake)
      throws NoSuchMetalakeException {
    try {
      NameIdentifier metalakeIdent = NameIdentifier.ofMetalake(metalake);
      if (!store.exists(metalakeIdent, Entity.EntityType.METALAKE)) {
        LOG.warn("Metalake {} does not exist", metalakeIdent);
        throw new NoSuchMetalakeException(METALAKE_DOES_NOT_EXIST_MSG, metalakeIdent);
      }
    } catch (IOException e) {
      LOG.error("Failed to do storage operation", e);
      throw new RuntimeException(e);
    }
  }

  public static <R, E extends Exception> R doWithLock(Executable<R, E> executable) throws E {
    synchronized (accessControlLock) {
      return executable.execute();
    }
  }

  public static boolean hasPrivilege(NameIdentifier ident, String privilege, String currentUser) {
    AccessControlManager accessControlManager = GravitinoEnv.getInstance().accessControlManager();
    String metalake;

    if (ident.hasNamespace()) {
      metalake = ident.namespace().level(0);
    } else {
      metalake = ident.name();
    }

    List<String> roles = accessControlManager.getUser(metalake, currentUser).roles();
    for (String roleName : roles) {
      Role role = accessControlManager.loadRole(metalake, roleName);
      if (isRolePermitted(ident, privilege, role)) {
        return true;
      }
    }

    Set<String> groups = accessControlManager.getGroupsByUser(currentUser);
    for (String groupName  : groups) {
      roles = accessControlManager.getGroup(metalake, groupName).roles();
      for (String roleName : roles) {
        Role role = accessControlManager.loadRole(metalake, roleName);
        if (isRolePermitted(ident, privilege, role)) {
          return true;
        }
      }
    }

    return false;
  }

  /*
  * Only the creator or the user who has the privilege can access the resource.
  * */
  public static void checkPermission(NameIdentifier ident, Function<NameIdentifier, Boolean> isCreatorFunc) {
    if (enableAuthorization()) {
      String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
      AuthorizationUtils.doWithLock(
              () -> {
                if (isCreatorFunc.apply(ident) && !AuthorizationUtils.hasPrivilege(ident, "", currentUser)) {
                  throw new ForbiddenException(
                          "%s doesn't have privilege to drop the catalog", currentUser);
                }

                return null;
              });
    }
  }

  public static boolean isCatalogCreator(NameIdentifier identifier) {
    String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
    CatalogManager catalogManager = GravitinoEnv.getInstance().catalogManager();;
    return currentUser.equals(catalogManager.loadCatalog(identifier).auditInfo().creator());
  }

  public static boolean isMetalakeCreator(NameIdentifier identifier) {
    String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
    MetalakeManager metalakeManager = GravitinoEnv.getInstance().metalakesManager();
    return currentUser.equals(metalakeManager.loadMetalake(identifier).auditInfo().creator());
  }

  private static boolean isRolePermitted(NameIdentifier ident, String privilege, Role role) {
    if (role.privileges().contains(privilege)) {
      if (role.privilegeEntityIdentifier().equals(ident)) {
        return true;
      }

      if (role.privilegeEntityIdentifier().equals(NameIdentifier.of(ident.namespace().levels()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean enableAuthorization() {
    return GravitinoEnv.getInstance().accessControlManager() != null;
  }
}
