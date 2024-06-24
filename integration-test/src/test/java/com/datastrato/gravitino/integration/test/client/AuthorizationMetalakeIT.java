/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.integration.test.client;

import com.datastrato.gravitino.Configs;
import com.datastrato.gravitino.MetalakeChange;
import com.datastrato.gravitino.auth.AuthConstants;
import com.datastrato.gravitino.client.GravitinoMetalake;
import com.datastrato.gravitino.exceptions.ForbiddenException;
import com.datastrato.gravitino.integration.test.util.AbstractIT;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuthorizationMetalakeIT extends AbstractIT {

  @BeforeAll
  public static void startIntegrationTest() throws Exception {
    // We start and stop the server for every case
  }

  @AfterAll
  public static void stopIntegrationTest() throws IOException, InterruptedException {
    // We start and stop the server for every case
  }

  @AfterEach
  public void stop() throws Exception {
    AbstractIT.stopIntegrationTest();
  }

  @Test
  public void testCreateMetalake() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), "true");
    configs.put(Configs.SERVICE_ADMINS.getKey(), AuthConstants.ANONYMOUS_USER);
    registerCustomConfigs(configs);
    AbstractIT.startIntegrationTest();

    // Forbid to create a metalake
    Assertions.assertThrows(
        ForbiddenException.class, () -> client.createMetalake("test", "", Collections.emptyMap()));

    // Allow to create a metalake
    client.addMetalakeAdmin(AuthConstants.ANONYMOUS_USER);
    GravitinoMetalake metalake = client.createMetalake("test", "", Collections.emptyMap());
    Assertions.assertEquals("test", metalake.name());
  }

  @Test
  public void testListMetalake() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), "true");
    configs.put(Configs.SERVICE_ADMINS.getKey(), AuthConstants.ANONYMOUS_USER);
    registerCustomConfigs(configs);
    AbstractIT.startIntegrationTest();

    client.addMetalakeAdmin(AuthConstants.ANONYMOUS_USER);
    client.createMetalake("test", "", Collections.emptyMap());
    GravitinoMetalake[] metalakes = client.listMetalakes();
    Assertions.assertEquals(1, metalakes.length);

    // Delete all privileges role
    client.deleteRole("test", "system_role_metalake_test");
    metalakes = client.listMetalakes();
    Assertions.assertEquals(1, metalakes.length);

    // Delete load metalake role
    client.deleteRole("test", "system_role_metalake_test_use_metalake");
    metalakes = client.listMetalakes();
    Assertions.assertEquals(0, metalakes.length);
  }

  @Test
  public void testLoadMetalake() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), "true");
    configs.put(Configs.SERVICE_ADMINS.getKey(), AuthConstants.ANONYMOUS_USER);
    registerCustomConfigs(configs);
    AbstractIT.startIntegrationTest();

    client.addMetalakeAdmin(AuthConstants.ANONYMOUS_USER);
    client.createMetalake("test", "", Collections.emptyMap());
    GravitinoMetalake metalake = client.loadMetalake("test");
    Assertions.assertEquals("test", metalake.name());

    // Delete all privileges role
    client.deleteRole("test", "system_role_metalake_test");
    metalake = client.loadMetalake("test");
    Assertions.assertEquals("test", metalake.name());

    // Delete load metalake role
    client.deleteRole("test", "system_role_metalake_test_use_metalake");
    Assertions.assertThrows(ForbiddenException.class, () -> client.loadMetalake("test"));
  }

  @Test
  public void testDropMetalake() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), "true");
    configs.put(Configs.SERVICE_ADMINS.getKey(), AuthConstants.ANONYMOUS_USER);
    registerCustomConfigs(configs);
    AbstractIT.startIntegrationTest();

    client.addMetalakeAdmin(AuthConstants.ANONYMOUS_USER);
    client.createMetalake("test", "", Collections.emptyMap());

    Assertions.assertTrue(client.dropMetalake("test"));

    client.createMetalake("test", "", Collections.emptyMap());

    // Delete all privileges role
    client.deleteRole("test", "system_role_metalake_test");

    Assertions.assertThrows(ForbiddenException.class, () -> client.dropMetalake("test"));
  }

  @Test
  public void testAlterMetalake() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), "true");
    configs.put(Configs.SERVICE_ADMINS.getKey(), AuthConstants.ANONYMOUS_USER);
    registerCustomConfigs(configs);
    AbstractIT.startIntegrationTest();

    client.addMetalakeAdmin(AuthConstants.ANONYMOUS_USER);
    client.createMetalake("test", "", Collections.emptyMap());

    GravitinoMetalake metalake = client.alterMetalake("test", MetalakeChange.rename("test1"));
    Assertions.assertEquals("test1", metalake.name());

    // Delete all privileges role
    client.deleteRole("test1", "system_role_metalake_test");

    Assertions.assertThrows(
        ForbiddenException.class,
        () -> client.alterMetalake("test1", MetalakeChange.rename("test2")));
  }
}
