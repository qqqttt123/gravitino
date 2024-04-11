/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.server.web.rest;

import com.codahale.metrics.annotation.ResponseMetered;
import com.codahale.metrics.annotation.Timed;
import com.datastrato.gravitino.GravitinoEnv;
import com.datastrato.gravitino.MetalakeChange;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.datastrato.gravitino.authorization.AccessControlManager;
import com.datastrato.gravitino.authorization.AuthorizationUtils;
import com.datastrato.gravitino.dto.MetalakeDTO;
import com.datastrato.gravitino.dto.requests.MetalakeCreateRequest;
import com.datastrato.gravitino.dto.requests.MetalakeUpdateRequest;
import com.datastrato.gravitino.dto.requests.MetalakeUpdatesRequest;
import com.datastrato.gravitino.dto.responses.DropResponse;
import com.datastrato.gravitino.dto.responses.MetalakeListResponse;
import com.datastrato.gravitino.dto.responses.MetalakeResponse;
import com.datastrato.gravitino.dto.util.DTOConverters;
import com.datastrato.gravitino.exceptions.ForbiddenException;
import com.datastrato.gravitino.lock.LockType;
import com.datastrato.gravitino.lock.TreeLockUtils;
import com.datastrato.gravitino.meta.BaseMetalake;
import com.datastrato.gravitino.metalake.MetalakeManager;
import com.datastrato.gravitino.metrics.MetricNames;
import com.datastrato.gravitino.server.web.Utils;
import com.datastrato.gravitino.utils.PrincipalUtils;
import java.util.Arrays;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * If Gravitino enables authorization, MetalakeOperations will follow the rules. First, only
 * metalake admins can create the metalake or list metalakes. Second, only metalake admins can alter
 * or drop the metalake which he created. Third, all the users of the metalake can load the
 * metalake.
 */
@Path("/metalakes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MetalakeOperations {

  private static final Logger LOG = LoggerFactory.getLogger(MetalakeOperations.class);

  private final MetalakeManager manager;
  private final AccessControlManager accessControlManager;

  @Context private HttpServletRequest httpRequest;

  @Inject
  public MetalakeOperations(MetalakeManager manager) {
    this.manager = manager;
    this.accessControlManager = GravitinoEnv.getInstance().accessControlManager();
  }

  @GET
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "list-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "list-metalake", absolute = true)
  public Response listMetalakes() {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            BaseMetalake[] metalakes =
                TreeLockUtils.doWithRootTreeLock(
                    LockType.READ,
                    () -> {
                      if (authorizationDisabled()) {
                        return manager.listMetalakes();

                      } else {
                        return AuthorizationUtils.doWithLock(
                            () -> {
                              String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
                              if (isMetalakeAdmin(currentUser)) {
                                return manager.listMetalakes();
                              } else {
                                throw new ForbiddenException(
                                    "%s is not a metalake admin, only metalake admins can create metalake",
                                    currentUser);
                              }
                            });
                      }
                    });
            MetalakeDTO[] metalakeDTOS =
                Arrays.stream(metalakes).map(DTOConverters::toDTO).toArray(MetalakeDTO[]::new);
            return Utils.ok(new MetalakeListResponse(metalakeDTOS));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(
          OperationType.LIST, Namespace.empty().toString(), e);
    }
  }

  @POST
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "create-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "create-metalake", absolute = true)
  public Response createMetalake(MetalakeCreateRequest request) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            request.validate();
            NameIdentifier ident = NameIdentifier.ofMetalake(request.getName());
            BaseMetalake metalake =
                TreeLockUtils.doWithRootTreeLock(
                    LockType.WRITE,
                    () -> {
                      if (authorizationDisabled()) {
                        return manager.createMetalake(
                            ident, request.getComment(), request.getProperties());

                      } else {
                        return AuthorizationUtils.doWithLock(
                            () -> {
                              String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
                              if (isMetalakeAdmin(currentUser)) {
                                return manager.createMetalake(
                                    ident, request.getComment(), request.getProperties());
                              } else {
                                throw new ForbiddenException(
                                    "%s is not a metalake amin, only metalake admins can create metalake",
                                    currentUser);
                              }
                            });
                      }
                    });
            return Utils.ok(new MetalakeResponse(DTOConverters.toDTO(metalake)));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.CREATE, request.getName(), e);
    }
  }

  @GET
  @Path("{name}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "load-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "load-metalake", absolute = true)
  public Response loadMetalake(@PathParam("name") String metalakeName) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifier.ofMetalake(metalakeName);
            BaseMetalake metalake =
                TreeLockUtils.doWithTreeLock(
                    identifier,
                    LockType.READ,
                    () -> {
                      if (authorizationDisabled()) {
                        return manager.loadMetalake(identifier);

                      } else {
                        return AuthorizationUtils.doWithLock(
                            () -> {
                              String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
                              if (isCreator(currentUser, identifier)
                                  || isUserInMetalake(currentUser, metalakeName)) {
                                return manager.loadMetalake(identifier);

                              } else {
                                throw new ForbiddenException(
                                    "%s is not in the metalake, he can't load the metalake",
                                    currentUser);
                              }
                            });
                      }
                    });
            return Utils.ok(new MetalakeResponse(DTOConverters.toDTO(metalake)));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.LOAD, metalakeName, e);
    }
  }

  @PUT
  @Path("{name}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "alter-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "alter-metalake", absolute = true)
  public Response alterMetalake(
      @PathParam("name") String metalakeName, MetalakeUpdatesRequest updatesRequest) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            updatesRequest.validate();
            NameIdentifier identifier = NameIdentifier.ofMetalake(metalakeName);
            MetalakeChange[] changes =
                updatesRequest.getUpdates().stream()
                    .map(MetalakeUpdateRequest::metalakeChange)
                    .toArray(MetalakeChange[]::new);
            BaseMetalake updatedMetalake =
                TreeLockUtils.doWithRootTreeLock(
                    LockType.WRITE,
                    () -> {
                      String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
                      if (authorizationDisabled() || isCreator(currentUser, identifier)) {
                        return manager.alterMetalake(identifier, changes);

                      } else {
                        throw new ForbiddenException(
                            "%s is not the creator of metalake, only the creator can alter the metalake",
                            currentUser);
                      }
                    });
            return Utils.ok(new MetalakeResponse(DTOConverters.toDTO(updatedMetalake)));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.ALTER, metalakeName, e);
    }
  }

  @DELETE
  @Path("{name}")
  @Produces("application/vnd.gravitino.v1+json")
  @Timed(name = "drop-metalake." + MetricNames.HTTP_PROCESS_DURATION, absolute = true)
  @ResponseMetered(name = "drop-metalake", absolute = true)
  public Response dropMetalake(@PathParam("name") String metalakeName) {
    try {
      return Utils.doAs(
          httpRequest,
          () -> {
            NameIdentifier identifier = NameIdentifier.ofMetalake(metalakeName);
            boolean dropped =
                TreeLockUtils.doWithRootTreeLock(
                    LockType.WRITE,
                    () -> {
                      String currentUser = PrincipalUtils.getCurrentPrincipal().getName();
                      if (authorizationDisabled() || isCreator(currentUser, identifier)) {
                        return manager.dropMetalake(identifier);

                      } else {
                        throw new ForbiddenException(
                            "%s is not the creator of the metalake, only the creator can drop the metalake",
                            currentUser);
                      }
                    });
            if (!dropped) {
              LOG.warn("Failed to drop metalake by name {}", metalakeName);
            }

            return Utils.ok(new DropResponse(dropped));
          });

    } catch (Exception e) {
      return ExceptionHandlers.handleMetalakeException(OperationType.DROP, metalakeName, e);
    }
  }

  private boolean authorizationDisabled() {
    return accessControlManager == null;
  }

  private boolean isCreator(String currentUser, NameIdentifier identifier) {
    return currentUser.equals(manager.loadMetalake(identifier).auditInfo().creator());
  }

  private boolean isMetalakeAdmin(String currentUser) {
    return accessControlManager.isMetalakeAdmin(currentUser);
  }

  private boolean isUserInMetalake(String currentUser, String metalake) {
    return accessControlManager.isUserInMetalake(currentUser, metalake);
  }
}
