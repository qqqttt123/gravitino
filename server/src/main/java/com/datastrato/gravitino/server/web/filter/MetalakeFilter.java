/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.filter;

import com.datastrato.gravitino.Entity;
import com.datastrato.gravitino.authorization.AuthorizationUtils;
import com.datastrato.gravitino.authorization.Privilege;
import com.datastrato.gravitino.authorization.Privileges;
import com.datastrato.gravitino.authorization.SecurableObject;
import com.datastrato.gravitino.authorization.SecurableObjects;
import com.datastrato.gravitino.exceptions.ForbiddenException;
import com.datastrato.gravitino.server.authorization.NameBindings;
import com.datastrato.gravitino.server.web.Utils;
import com.datastrato.gravitino.server.web.rest.OperationType;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.List;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;

@Provider
@NameBindings.MetalakeInterface
public class MetalakeFilter implements BasedRoleFilter {
  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    try {
      AuthorizationUtils.checkPermission(
          getMetalakeName(requestContext), getSecurableObject(requestContext));

    } catch (ForbiddenException fe) {
      requestContext.abortWith(
          Utils.forbidden(
              String.format("Fail to operate [%s] metalake", getOperateType(requestContext)), fe));
    } catch (IllegalArgumentException ie) {
      requestContext.abortWith(
          Utils.illegalArguments(
              String.format("Fail to operate [%s] metalake ", getOperateType(requestContext)), ie));
    }
  }

  @Override
  public SecurableObject getSecurableObject(ContainerRequestContext requestContext) {
    List<Privilege> privileges = Lists.newArrayList();
    OperationType operationType = getOperateType(requestContext);

    switch (operationType) {
      case CREATE:
        privileges.add(Privileges.CreateMetalake.allow());
        return SecurableObjects.ofAllMetalakes(privileges);
      case LIST:
        // List metalake doesn't need any privilege
        return SecurableObjects.ofAllMetalakes(privileges);
      case LOAD:
        privileges.add(Privileges.UseMetalake.allow());
        return SecurableObjects.ofMetalake(getMetalakeName(requestContext), privileges);
      case DROP:
      case ALTER:
        privileges.add(Privileges.ManageMetalake.allow());
        return SecurableObjects.ofMetalake(getMetalakeName(requestContext), privileges);
      default:
        throw new IllegalArgumentException(
            String.format("Filter doesn't support %s HTTP method", requestContext.getMethod()));
    }
  }

  @Override
  public String getMetalakeName(ContainerRequestContext requestContext) {
    MultivaluedMap<String, String> pathParameters = requestContext.getUriInfo().getPathParameters();
    String metalake = pathParameters.getFirst("name");
    if (metalake == null) {
      return Entity.SYSTEM_METALAKE_RESERVED_NAME;
    }
    return metalake;
  }

  @Override
  public OperationType getOperateType(ContainerRequestContext requestContext) {
    switch (requestContext.getMethod()) {
      case POST:
        return OperationType.CREATE;
      case PUT:
        return OperationType.ALTER;
      case GET:
        if (getMetalakeName(requestContext).equals(Entity.SYSTEM_METALAKE_RESERVED_NAME)) {
          return OperationType.LIST;
        } else {
          return OperationType.LOAD;
        }
      case DELETE:
        return OperationType.DROP;
      default:
        throw new IllegalArgumentException(
            String.format("Filter doesn't support %s HTTP method", requestContext.getMethod()));
    }
  }
}
