/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.authorization;

import com.datastrato.gravitino.*;
import com.datastrato.gravitino.exceptions.GroupAlreadyExistsException;
import com.datastrato.gravitino.exceptions.NoSuchGroupException;
import com.datastrato.gravitino.exceptions.NoSuchRoleException;
import com.datastrato.gravitino.exceptions.NoSuchUserException;
import com.datastrato.gravitino.exceptions.RoleAlreadyExistsException;
import com.datastrato.gravitino.exceptions.UserAlreadyExistsException;
import com.datastrato.gravitino.storage.IdGenerator;
import com.datastrato.gravitino.utils.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AccessControlManager is used for manage users, roles, admin, grant information, this class is an
 * entrance class for tenant management. This lock policy about this is as follows: First, admin
 * operations are prevented by one lock. Then, other operations are prevented by the other lock. For
 * non-admin operations, Gravitino doesn't choose metalake level lock. There are some reasons
 * mainly: First, the metalake can be renamed by users. It's hard to maintain a map with metalake as
 * the key. Second, the lock will be couped with life cycle of the metalake.
 */
public class AccessControlManager {

  private static final Logger LOG = LoggerFactory.getLogger(AccessControlManager.class);

  private final UserGroupManager userGroupManager;
  private final AdminManager adminManager;
  private final RoleManager roleManager;
  private final GrantManager grantManager;
  private final GroupMappingServiceProvider groupMappingServiceProvider;
  private final Object adminOperationLock = new Object();
  private final Object nonAdminOperationLock = new Object();

  public AccessControlManager(EntityStore store, IdGenerator idGenerator, Config config) {
    this.userGroupManager = new UserGroupManager(store, idGenerator);
    this.adminManager = new AdminManager(store, idGenerator, config);
    this.roleManager = new RoleManager(store, idGenerator);
    this.grantManager = new GrantManager(store);

    String userGroupsMappingClass = config.get(Configs.USER_GROUP_MAPPING);
    if (userGroupsMappingClass != null) {
      try {
        groupMappingServiceProvider = (GroupMappingServiceProvider) Class.forName(userGroupsMappingClass).getDeclaredConstructor().newInstance();
      } catch (Exception e) {
        LOG.error("Failed to create and initialize group mapping service provider by name {}.", userGroupsMappingClass, e);
        throw new RuntimeException("Failed to create and initialize group mapping service provider: " + userGroupsMappingClass, e);
      }
    } else {
      groupMappingServiceProvider = null;
    }
  }

  /**
   * Adds a new User.
   *
   * @param metalake The Metalake of the User.
   * @param name The name of the User.
   * @return The added User instance.
   * @throws UserAlreadyExistsException If a User with the same identifier already exists.
   * @throws RuntimeException If adding the User encounters storage issues.
   */
  public User addUser(String metalake, String name) throws UserAlreadyExistsException {
    return doWithNonAdminLock(() -> userGroupManager.addUser(metalake, name));
  }

  /**
   * Removes a User.
   *
   * @param metalake The Metalake of the User.
   * @param user The name of the User.
   * @return `true` if the User was successfully removed, `false` otherwise.
   * @throws RuntimeException If removing the User encounters storage issues.
   */
  public boolean removeUser(String metalake, String user) {
    return doWithNonAdminLock(() -> userGroupManager.removeUser(metalake, user));
  }

  /**
   * Gets a User.
   *
   * @param metalake The Metalake of the User.
   * @param user The name of the User.
   * @return The getting User instance.
   * @throws NoSuchUserException If the User with the given identifier does not exist.
   * @throws RuntimeException If getting the User encounters storage issues.
   */
  public User getUser(String metalake, String user) throws NoSuchUserException {
    return doWithNonAdminLock(() -> userGroupManager.getUser(metalake, user));
  }

  /**
   * Judges whether the user is in the metalake.
   *
   * @param user The name of the User
   * @param metalake The name of the Metalake
   * @return true, if the user is in the metalake, otherwise false.
   */
  public boolean isUserInMetalake(String user, String metalake) {
    return doWithNonAdminLock(() -> userGroupManager.isUserInMetalake(user, metalake));
  }

  /**
   * Adds a new Group.
   *
   * @param metalake The Metalake of the Group.
   * @param group The name of the Group.
   * @return The Added Group instance.
   * @throws GroupAlreadyExistsException If a Group with the same identifier already exists.
   * @throws RuntimeException If adding the Group encounters storage issues.
   */
  public Group addGroup(String metalake, String group) throws GroupAlreadyExistsException {
    return doWithNonAdminLock(() -> userGroupManager.addGroup(metalake, group));
  }

  /**
   * Removes a Group.
   *
   * @param metalake The Metalake of the Group.
   * @param group THe name of the Group.
   * @return `true` if the Group was successfully removed, `false` otherwise.
   * @throws RuntimeException If removing the Group encounters storage issues.
   */
  public boolean removeGroup(String metalake, String group) {
    return doWithNonAdminLock(() -> userGroupManager.removeGroup(metalake, group));
  }

  /**
   * Gets a Group.
   *
   * @param metalake The Metalake of the Group.
   * @param group THe name of the Group.
   * @return The getting Group instance.
   * @throws NoSuchGroupException If the Group with the given identifier does not exist.
   * @throws RuntimeException If getting the Group encounters storage issues.
   */
  public Group getGroup(String metalake, String group) throws NoSuchGroupException {
    return doWithNonAdminLock(() -> userGroupManager.getGroup(metalake, group));
  }

  public boolean addRoleToUser(String metalake, String role, String user) {
    return doWithNonAdminLock(() -> grantManager.addRoleToUser(metalake, role, user));
  }

  public boolean addRoleToGroup(String metalake, String role, String group) {
    return doWithNonAdminLock(() -> grantManager.addRoleToGroup(metalake, role, group));
  }

  public synchronized boolean removeRoleFromGroup(String metalake, String role, String group) {
    return doWithNonAdminLock(() -> grantManager.removeRoleFromGroup(metalake, role, group));
  }

  public synchronized boolean removeRoleFromUser(String metalake, String role, String user) {
    return doWithNonAdminLock(() -> grantManager.removeRoleFromUser(metalake, role, user));
  }

  /**
   * Adds a new metalake admin.
   *
   * @param user The name of the User.
   * @return The added User instance.
   * @throws UserAlreadyExistsException If a User with the same identifier already exists.
   * @throws RuntimeException If adding the User encounters storage issues.
   */
  public User addMetalakeAdmin(String user) {
    return doWithAdminLock(() -> adminManager.addMetalakeAdmin(user));
  }

  /**
   * Removes a metalake admin.
   *
   * @param user The name of the User.
   * @return `true` if the User was successfully removed, `false` otherwise.
   * @throws RuntimeException If removing the User encounters storage issues.
   */
  public boolean removeMetalakeAdmin(String user) {
    return doWithAdminLock(() -> adminManager.removeMetalakeAdmin(user));
  }

  /**
   * Judges whether the user is the service admin.
   *
   * @param user the name of the user
   * @return true, if the user is service admin, otherwise false.
   */
  public boolean isServiceAdmin(String user) {
    return adminManager.isServiceAdmin(user);
  }

  /**
   * Judges whether the user is the metalake admin.
   *
   * @param user the name of the user
   * @return true, if the user is metalake admin, otherwise false.
   */
  public boolean isMetalakeAdmin(String user) {
    return doWithAdminLock(() -> adminManager.isMetalakeAdmin(user));
  }

  /**
   * Creates a new Role.
   *
   * @param metalake The Metalake of the Role.
   * @param role The name of the Role.
   * @param properties The properties of the Role.
   * @param privilegeEntityIdentifier The privilege entity identifier of the Role.
   * @param privilegeEntityType The privilege entity type of the Role.
   * @param privileges The privileges of the Role.
   * @return The created Role instance.
   * @throws RoleAlreadyExistsException If a Role with the same identifier already exists.
   * @throws RuntimeException If creating the Role encounters storage issues.
   */
  public Role createRole(
      String metalake,
      String role,
      Map<String, String> properties,
      NameIdentifier privilegeEntityIdentifier,
      Entity.EntityType privilegeEntityType,
      List<Privilege> privileges)
      throws RoleAlreadyExistsException {
    return doWithNonAdminLock(
        () ->
            roleManager.createRole(
                metalake,
                role,
                properties,
                privilegeEntityIdentifier,
                privilegeEntityType,
                privileges));
  }

  /**
   * Loads a Role.
   *
   * @param metalake The Metalake of the Role.
   * @param role The name of the Role.
   * @return The loading Role instance.
   * @throws NoSuchRoleException If the Role with the given identifier does not exist.
   * @throws RuntimeException If loading the Role encounters storage issues.
   */
  public Role loadRole(String metalake, String role) throws NoSuchRoleException {
    return doWithNonAdminLock(() -> roleManager.loadRole(metalake, role));
  }

  /**
   * Drops a Role.
   *
   * @param metalake The Metalake of the Role.
   * @param role The name of the Role.
   * @return `true` if the Role was successfully dropped, `false` otherwise.
   * @throws RuntimeException If dropping the User encounters storage issues.
   */
  public boolean dropRole(String metalake, String role) {
    return doWithNonAdminLock(() -> roleManager.dropRole(metalake, role));
  }

  public Set<String> getGroupsByUser(String user) {
    if (groupMappingServiceProvider == null) {
      return Collections.emptySet();
    }

    return groupMappingServiceProvider.getGroups(user);
  }

  private <R, E extends Exception> R doWithNonAdminLock(Executable<R, E> executable) throws E {
    synchronized (nonAdminOperationLock) {
      return executable.execute();
    }
  }

  private <R, E extends Exception> R doWithAdminLock(Executable<R, E> executable) throws E {
    synchronized (adminOperationLock) {
      return executable.execute();
    }
  }
}
