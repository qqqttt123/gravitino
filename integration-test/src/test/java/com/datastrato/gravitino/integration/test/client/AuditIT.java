/*
 * Copyright 2023 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */

package com.datastrato.gravitino.integration.test.client;

import com.datastrato.gravitino.MetalakeChange;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.UserPrincipal;
import com.datastrato.gravitino.auth.AuthenticatorType;
import com.datastrato.gravitino.client.GravitinoMetaLake;
import com.datastrato.gravitino.integration.test.util.AbstractIT;
import com.datastrato.gravitino.integration.test.util.GravitinoITUtils;
import com.datastrato.gravitino.server.auth.OAuthConfig;
import com.google.common.collect.Maps;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Map;
import javax.security.auth.Subject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class AuditIT extends AbstractIT {

  private static final String expectUser = System.getProperty("user.name");

  @BeforeAll
  public static void startIntegrationTest() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(OAuthConfig.AUTHENTICATOR.getKey(), AuthenticatorType.SIMPLE.name().toLowerCase());
    registerCustomConfigs(configs);
    AbstractIT.startIntegrationTest();
  }

  @Test
  public void testAuditMetalake() throws Exception {
    String metalakeAuditName = GravitinoITUtils.genRandomName("metalakeAudit");
    String newName = GravitinoITUtils.genRandomName("newmetaname");

    GravitinoMetaLake metaLake =
        client.createMetalake(
            NameIdentifier.parse(metalakeAuditName), "metalake A comment", Collections.emptyMap());
    Assertions.assertEquals(expectUser, metaLake.auditInfo().creator());
    Assertions.assertNull(metaLake.auditInfo().lastModifier());
    MetalakeChange[] changes =
        new MetalakeChange[] {
          MetalakeChange.rename(newName), MetalakeChange.updateComment("new metalake comment")
        };
    metaLake = client.alterMetalake(NameIdentifier.of(metalakeAuditName), changes);
    Assertions.assertEquals(expectUser, metaLake.auditInfo().creator());
    Assertions.assertEquals(expectUser, metaLake.auditInfo().lastModifier());
    client.dropMetalake(NameIdentifier.parse(newName));
  }

  @Test
  public void testSubject() {
    Subject subject = new Subject();
    subject.getPrincipals().add(new UserPrincipal("test"));
    Subject.doAs(
        subject,
        (PrivilegedAction<Object>)
            () -> {
              Thread thread =
                  new Thread(
                      () -> {
                        AccessControlContext context = AccessController.getContext();
                        Subject subject1 = Subject.getSubject(context);
                        UserPrincipal principal =
                            subject1.getPrincipals(UserPrincipal.class).iterator().next();
                        System.out.println("principal name: " + principal.getName());
                      });
              thread.start();
              try {
                thread.join();
              } catch (Exception e) {
                // e.printStackTrace();
              }
              return null;
            });
  }
}
