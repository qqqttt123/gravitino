/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.filter;

import static org.mockito.ArgumentMatchers.any;

import com.datastrato.gravitino.GravitinoEnv;
import com.datastrato.gravitino.authorization.AccessControlManager;
import com.datastrato.gravitino.authorization.Privilege;
import com.datastrato.gravitino.authorization.Privileges;
import com.datastrato.gravitino.authorization.SecurableObjects;
import com.datastrato.gravitino.meta.RoleEntity;
import com.google.common.collect.Lists;
import java.io.IOException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class TestMetalakeFilter {
  private static final ContainerRequestContext requestContext =
      Mockito.mock(ContainerRequestContext.class);
  private static final AccessControlManager accessControlManager =
      Mockito.mock(AccessControlManager.class);
  private static final RoleEntity roleEntity = Mockito.mock(RoleEntity.class);
  private static final MultivaluedMap<String, String> pathParams =
      Mockito.mock(MultivaluedMap.class);

  @BeforeAll
  static void start() throws IllegalAccessException {
    FieldUtils.writeField(
        GravitinoEnv.getInstance(), "accessControlManager", accessControlManager, true);
    Mockito.when(accessControlManager.getRolesByUserFromMetalake(any(), any()))
        .thenReturn(Lists.newArrayList(roleEntity));
    UriInfo uriInfo = Mockito.mock(UriInfo.class);
    Mockito.when(requestContext.getUriInfo()).thenReturn(uriInfo);
    Mockito.when(uriInfo.getPathParameters()).thenReturn(pathParams);
  }

  @Test
  void testMetalakeFilterCreateAllow() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.POST);
    Mockito.when(pathParams.getFirst(any())).thenReturn(null);
    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofAllMetalakes(
                    Lists.newArrayList(Privileges.CreateMetalake.allow())),
                Privilege.Name.CREATE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(true);
    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofAllMetalakes(
                    Lists.newArrayList(Privileges.CreateMetalake.allow())),
                Privilege.Name.CREATE_METALAKE,
                Privilege.Condition.DENY))
        .thenReturn(false);
    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext, Mockito.never()).abortWith(any());
  }

  @Test
  void testMetalakeFilterCreateDeny() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.POST);
    Mockito.when(pathParams.getFirst(any())).thenReturn(null);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofAllMetalakes(
                    Lists.newArrayList(Privileges.CreateMetalake.allow())),
                Privilege.Name.CREATE_METALAKE,
                Privilege.Condition.DENY))
        .thenReturn(true);
    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext).abortWith(any());
  }

  @Test
  void testMetalakeFilterListAllow() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.GET);
    Mockito.when(pathParams.getFirst(any())).thenReturn(null);
    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext, Mockito.never()).abortWith(any());
  }

  @Test
  void testMetalakeFilterLoadAllow() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.GET);
    String metalake = "metalake";
    Mockito.when(pathParams.getFirst(any())).thenReturn(metalake);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.UseMetalake.allow())),
                Privilege.Name.USE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(true);
    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.UseMetalake.allow())),
                Privilege.Name.USE_METALAKE,
                Privilege.Condition.DENY))
        .thenReturn(false);

    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext, Mockito.never()).abortWith(any());
  }

  @Test
  void testMetalakeFilterLoadDeny() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.GET);
    String metalake = "metalake";
    Mockito.when(pathParams.getFirst(any())).thenReturn(metalake);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.UseMetalake.allow())),
                Privilege.Name.USE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(false);

    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext).abortWith(any());
  }

  @Test
  void testMetalakeFilterDropAllow() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.DELETE);
    String metalake = "metalake";
    Mockito.when(pathParams.getFirst(any())).thenReturn(metalake);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.ManageMetalake.allow())),
                Privilege.Name.MANAGE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(true);
    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.ManageMetalake.allow())),
                Privilege.Name.MANAGE_METALAKE,
                Privilege.Condition.DENY))
        .thenReturn(false);

    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext, Mockito.never()).abortWith(any());
  }

  @Test
  void testMetalakeFilterDropDeny() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.DELETE);
    String metalake = "metalake";
    Mockito.when(pathParams.getFirst(any())).thenReturn(metalake);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.ManageMetalake.allow())),
                Privilege.Name.MANAGE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(false);

    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext).abortWith(any());
  }

  @Test
  void testMetalakeFilterAlterAllow() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.PUT);
    String metalake = "metalake";
    Mockito.when(pathParams.getFirst(any())).thenReturn(metalake);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.ManageMetalake.allow())),
                Privilege.Name.MANAGE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(true);
    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.ManageMetalake.allow())),
                Privilege.Name.MANAGE_METALAKE,
                Privilege.Condition.DENY))
        .thenReturn(false);

    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext, Mockito.never()).abortWith(any());
  }

  @Test
  void testMetalakeFilterAlterDeny() throws IOException {
    MetalakeFilter metalakeFilter = new MetalakeFilter();
    Mockito.when(requestContext.getMethod()).thenReturn(BasedRoleFilter.PUT);
    String metalake = "metalake";
    Mockito.when(pathParams.getFirst(any())).thenReturn(metalake);

    Mockito.when(
            roleEntity.hasPrivilegeWithCondition(
                SecurableObjects.ofMetalake(
                    metalake, Lists.newArrayList(Privileges.ManageMetalake.allow())),
                Privilege.Name.MANAGE_METALAKE,
                Privilege.Condition.ALLOW))
        .thenReturn(false);

    metalakeFilter.filter(requestContext);
    Mockito.verify(requestContext).abortWith(any());
  }
}
