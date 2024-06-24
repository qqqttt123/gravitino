/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.filter;

import com.datastrato.gravitino.Entity;
import com.datastrato.gravitino.UserPrincipal;
import com.datastrato.gravitino.auth.AuthConstants;
import com.datastrato.gravitino.authorization.AuthorizationUtils;
import com.datastrato.gravitino.authorization.Privilege;
import com.datastrato.gravitino.authorization.Privileges;
import com.datastrato.gravitino.authorization.SecurableObject;
import com.datastrato.gravitino.authorization.SecurableObjects;
import com.datastrato.gravitino.dto.MetalakeDTO;
import com.datastrato.gravitino.dto.responses.MetalakeListResponse;
import com.datastrato.gravitino.dto.responses.MetalakeResponse;
import com.datastrato.gravitino.exceptions.ForbiddenException;
import com.datastrato.gravitino.server.authorization.NameBindings;
import com.datastrato.gravitino.server.web.rest.OperationType;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

@Provider
@NameBindings.MetalakeInterface
public class MetalakeResponseFilter implements ContainerResponseFilter {

  private final List<Privilege> allMetalakePrivileges =
      Lists.newArrayList(
          Privileges.UseMetalake.allow(),
          Privileges.ManageMetalake.allow(),
          Privileges.UseCatalog.allow(),
          Privileges.CreateCatalog.allow(),
          Privileges.AlterCatalog.allow(),
          Privileges.DropCatalog.allow(),
          Privileges.CreateRole.allow(),
          Privileges.GetRole.allow(),
          Privileges.DeleteRole.allow(),
          Privileges.AddUser.allow(),
          Privileges.GetUser.allow(),
          Privileges.RemoveUser.allow(),
          Privileges.AddGroup.allow(),
          Privileges.RemoveGroup.allow(),
          Privileges.GetGroup.allow(),
          Privileges.GrantRole.allow(),
          Privileges.RevokeRole.allow());

  @Override
  public void filter(
      ContainerRequestContext requestContext, ContainerResponseContext responseContext)
      throws IOException {
    if (getOperateType(requestContext).equals(OperationType.LIST)) {
      if (responseContext.getStatus() == Response.Status.OK.getStatusCode()) {
        MetalakeListResponse response = (MetalakeListResponse) responseContext.getEntity();
        List<MetalakeDTO> filteredMetalakes = Lists.newArrayList();
        for (MetalakeDTO metalakeDTO : response.getMetalakes()) {
          try {
            AuthorizationUtils.checkPermission(
                metalakeDTO.name(),
                SecurableObjects.ofMetalake(metalakeDTO.name(), allMetalakePrivileges));
            filteredMetalakes.add(metalakeDTO);
          } catch (ForbiddenException fe) {
            // ignore the metalake
          }
        }
        responseContext.setEntity(
            new MetalakeListResponse(filteredMetalakes.toArray(new MetalakeDTO[0])));
      }
    } else if (getOperateType(requestContext).equals(OperationType.CREATE)) {
      if (responseContext.getStatus() == Response.Status.OK.getStatusCode()) {
        MetalakeResponse response = (MetalakeResponse) responseContext.getEntity();
        SecurableObject securableObject =
            SecurableObjects.ofMetalake(response.getMetalake().name(), allMetalakePrivileges);
        AuthorizationUtils.createAndGrantSystemRoleForSecurableObject(
            response.getMetalake().name(),
            securableObject,
            ((UserPrincipal)
                    requestContext.getProperty(
                        AuthConstants.AUTHENTICATED_PRINCIPAL_ATTRIBUTE_NAME))
                .getName());
      }
    }
  }

  public OperationType getOperateType(ContainerRequestContext requestContext) {
    switch (requestContext.getMethod()) {
      case HttpMethod.POST:
        return OperationType.CREATE;
      case HttpMethod.PUT:
        return OperationType.ALTER;
      case HttpMethod.GET:
        if (getMetalakeName(requestContext).equals(Entity.SYSTEM_METALAKE_RESERVED_NAME)) {
          return OperationType.LIST;
        } else {
          return OperationType.LOAD;
        }
      case HttpMethod.DELETE:
        return OperationType.DROP;
      default:
        throw new IllegalArgumentException(
            String.format("Filter doesn't support %s HTTP method", requestContext.getMethod()));
    }
  }

  public String getMetalakeName(ContainerRequestContext requestContext) {
    MultivaluedMap<String, String> pathParameters = requestContext.getUriInfo().getPathParameters();
    String metalake = pathParameters.getFirst("name");
    if (metalake == null) {
      return Entity.SYSTEM_METALAKE_RESERVED_NAME;
    }
    return metalake;
  }
}
