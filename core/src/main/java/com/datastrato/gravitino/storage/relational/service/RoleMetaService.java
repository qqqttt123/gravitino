/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.storage.relational.service;

import com.datastrato.gravitino.Entity;
import com.datastrato.gravitino.MetadataObject;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.authorization.AuthorizationUtils;
import com.datastrato.gravitino.authorization.SecurableObject;
import com.datastrato.gravitino.exceptions.NoSuchEntityException;
import com.datastrato.gravitino.meta.RoleEntity;
import com.datastrato.gravitino.storage.relational.mapper.GroupRoleRelMapper;
import com.datastrato.gravitino.storage.relational.mapper.RoleMetaMapper;
import com.datastrato.gravitino.storage.relational.mapper.SecurableObjectMapper;
import com.datastrato.gravitino.storage.relational.mapper.UserRoleRelMapper;
import com.datastrato.gravitino.storage.relational.po.CatalogPO;
import com.datastrato.gravitino.storage.relational.po.FilesetPO;
import com.datastrato.gravitino.storage.relational.po.MetalakePO;
import com.datastrato.gravitino.storage.relational.po.RolePO;
import com.datastrato.gravitino.storage.relational.po.SchemaPO;
import com.datastrato.gravitino.storage.relational.po.SecurableObjectPO;
import com.datastrato.gravitino.storage.relational.po.TablePO;
import com.datastrato.gravitino.storage.relational.po.TopicPO;
import com.datastrato.gravitino.storage.relational.utils.ExceptionUtils;
import com.datastrato.gravitino.storage.relational.utils.POConverters;
import com.datastrato.gravitino.storage.relational.utils.SessionUtils;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import java.util.List;

/** The service class for role metadata. It provides the basic database operations for role. */
public class RoleMetaService {
  private static final RoleMetaService INSTANCE = new RoleMetaService();
  private static final Splitter DOT_SPLITTER = Splitter.on('.');
  private static final String ROOT = "ROOT";
  private static final String ALL_METALAKES = "*";
  private static final long ALL_METALAKES_ENTITY_ID = 0;

  public static RoleMetaService getInstance() {
    return INSTANCE;
  }

  private RoleMetaService() {}

  private RolePO getRolePOByMetalakeIdAndName(Long metalakeId, String roleName) {
    RolePO rolePO =
        SessionUtils.getWithoutCommit(
            RoleMetaMapper.class,
            mapper -> mapper.selectRoleMetaByMetalakeIdAndName(metalakeId, roleName));

    if (rolePO == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.ROLE.name().toLowerCase(),
          roleName);
    }
    return rolePO;
  }

  public Long getRoleIdByMetalakeIdAndName(Long metalakeId, String roleName) {
    Long roleId =
        SessionUtils.getWithoutCommit(
            RoleMetaMapper.class,
            mapper -> mapper.selectRoleIdByMetalakeIdAndName(metalakeId, roleName));

    if (roleId == null) {
      throw new NoSuchEntityException(
          NoSuchEntityException.NO_SUCH_ENTITY_MESSAGE,
          Entity.EntityType.ROLE.name().toLowerCase(),
          roleName);
    }
    return roleId;
  }

  public List<RolePO> listRolesByUserId(Long userId) {
    return SessionUtils.getWithoutCommit(
        RoleMetaMapper.class, mapper -> mapper.listRolesByUserId(userId));
  }

  public List<RolePO> listRolesByGroupId(Long groupId) {
    return SessionUtils.getWithoutCommit(
        RoleMetaMapper.class, mapper -> mapper.listRolesByGroupId(groupId));
  }

  public void insertRole(RoleEntity roleEntity, boolean overwritten) {
    try {
      AuthorizationUtils.checkRole(roleEntity.nameIdentifier());

      Long metalakeId =
          MetalakeMetaService.getInstance().getMetalakeIdByName(roleEntity.namespace().level(0));
      RolePO.Builder builder = RolePO.builder().withMetalakeId(metalakeId);
      RolePO rolePO = POConverters.initializeRolePOWithVersion(roleEntity, builder);
      List<SecurableObjectPO> securableObjectPOs = Lists.newArrayList();
      for (SecurableObject object : roleEntity.securableObjects()) {
        SecurableObjectPO.Builder objectBuilder =
            POConverters.initializeSecurablePOBuilderWithVersion(
                roleEntity.id(), object, getEntityType(object));
        objectBuilder.withEntityId(
            getSecurableObjectEntityId(metalakeId, object.fullName(), object.type()));
        securableObjectPOs.add(objectBuilder.build());
      }

      SessionUtils.doMultipleWithCommit(
          () ->
              SessionUtils.doWithoutCommit(
                  SecurableObjectMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.softDeleteSecurableObjectsByRoleId(rolePO.getRoleId());
                    }
                    if (!securableObjectPOs.isEmpty()) {
                      mapper.batchInsertSecurableObjects(securableObjectPOs);
                    }
                  }),
          () ->
              SessionUtils.doWithoutCommit(
                  RoleMetaMapper.class,
                  mapper -> {
                    if (overwritten) {
                      mapper.insertRoleMetaOnDuplicateKeyUpdate(rolePO);
                    } else {
                      mapper.insertRoleMeta(rolePO);
                    }
                  }));

    } catch (RuntimeException re) {
      ExceptionUtils.checkSQLException(
          re, Entity.EntityType.ROLE, roleEntity.nameIdentifier().toString());
      throw re;
    }
  }

  public RoleEntity getRoleByIdentifier(NameIdentifier identifier) {
    AuthorizationUtils.checkRole(identifier);

    Long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    RolePO rolePO = getRolePOByMetalakeIdAndName(metalakeId, identifier.name());

    List<SecurableObjectPO> securableObjectPOs = listSecurableObjectsByRoleId(rolePO.getRoleId());
    List<SecurableObject> securableObjects = Lists.newArrayList();

    for (SecurableObjectPO securableObjectPO : securableObjectPOs) {
      String fullName = getSecurableObjectFullName(securableObjectPO);
      if (fullName != null) {
        securableObjects.add(
            POConverters.fromSecurableObjectPO(
                fullName, securableObjectPO, getType(securableObjectPO.getType())));
      }
    }

    return POConverters.fromRolePO(rolePO, securableObjects, identifier.namespace());
  }

  public boolean deleteRole(NameIdentifier identifier) {
    AuthorizationUtils.checkRole(identifier);

    Long metalakeId =
        MetalakeMetaService.getInstance().getMetalakeIdByName(identifier.namespace().level(0));
    Long roleId = getRoleIdByMetalakeIdAndName(metalakeId, identifier.name());

    SessionUtils.doMultipleWithCommit(
        () ->
            SessionUtils.doWithoutCommit(
                RoleMetaMapper.class, mapper -> mapper.softDeleteRoleMetaByRoleId(roleId)),
        () ->
            SessionUtils.doWithoutCommit(
                UserRoleRelMapper.class, mapper -> mapper.softDeleteUserRoleRelByRoleId(roleId)),
        () ->
            SessionUtils.doWithoutCommit(
                GroupRoleRelMapper.class, mapper -> mapper.softDeleteGroupRoleRelByRoleId(roleId)),
        () ->
            SessionUtils.doWithoutCommit(
                SecurableObjectMapper.class,
                mapper -> mapper.softDeleteSecurableObjectsByRoleId(roleId)));
    return true;
  }

  private List<SecurableObjectPO> listSecurableObjectsByRoleId(Long roleId) {
    return SessionUtils.getWithoutCommit(
        SecurableObjectMapper.class, mapper -> mapper.listSecurableObjectsByRoleId(roleId));
  }

  public int deleteRoleMetasByLegacyTimeLine(long legacyTimeLine, int limit) {
    int[] roleDeletedCount = new int[] {0};
    int[] userRoleRelDeletedCount = new int[] {0};
    int[] groupRoleRelDeletedCount = new int[] {0};
    int[] securableObjectsCount = new int[] {0};

    SessionUtils.doMultipleWithCommit(
        () ->
            roleDeletedCount[0] =
                SessionUtils.doWithoutCommitAndFetchResult(
                    RoleMetaMapper.class,
                    mapper -> mapper.deleteRoleMetasByLegacyTimeLine(legacyTimeLine, limit)),
        () ->
            userRoleRelDeletedCount[0] =
                SessionUtils.doWithoutCommitAndFetchResult(
                    UserRoleRelMapper.class,
                    mapper -> mapper.deleteUserRoleRelMetasByLegacyTimeLine(legacyTimeLine, limit)),
        () ->
            groupRoleRelDeletedCount[0] =
                SessionUtils.doWithoutCommitAndFetchResult(
                    GroupRoleRelMapper.class,
                    mapper ->
                        mapper.deleteGroupRoleRelMetasByLegacyTimeLine(legacyTimeLine, limit)),
        () ->
            securableObjectsCount[0] =
                SessionUtils.doWithoutCommitAndFetchResult(
                    SecurableObjectMapper.class,
                    mapper ->
                        mapper.deleteSecurableObjectsByLegacyTimeLine(legacyTimeLine, limit)));

    return roleDeletedCount[0]
        + userRoleRelDeletedCount[0]
        + groupRoleRelDeletedCount[0]
        + securableObjectsCount[0];
  }

  long getSecurableObjectEntityId(long metalakeId, String fullName, MetadataObject.Type type) {
    if (fullName.equals(ALL_METALAKES) && type == MetadataObject.Type.METALAKE) {
      return ALL_METALAKES_ENTITY_ID;
    }

    if (type == MetadataObject.Type.METALAKE) {
      return MetalakeMetaService.getInstance().getMetalakeIdByName(fullName);
    }

    List<String> names = DOT_SPLITTER.splitToList(fullName);
    long catalogId =
        CatalogMetaService.getInstance().getCatalogIdByMetalakeIdAndName(metalakeId, names.get(0));
    if (type == MetadataObject.Type.CATALOG) {
      return catalogId;
    }

    long schemaId =
        SchemaMetaService.getInstance().getSchemaIdByCatalogIdAndName(catalogId, names.get(1));
    if (type == MetadataObject.Type.SCHEMA) {
      return schemaId;
    }

    if (type == MetadataObject.Type.FILESET) {
      return FilesetMetaService.getInstance().getFilesetIdBySchemaIdAndName(schemaId, names.get(2));
    } else if (type == MetadataObject.Type.TOPIC) {
      return TopicMetaService.getInstance().getTopicIdBySchemaIdAndName(schemaId, names.get(2));
    } else if (type == MetadataObject.Type.TABLE) {
      return TableMetaService.getInstance().getTableIdBySchemaIdAndName(schemaId, names.get(2));
    }

    throw new IllegalArgumentException(String.format("Doesn't support the type %s", type));
  }

  String getSecurableObjectFullName(SecurableObjectPO securableObjectPO) {
    if (securableObjectPO.getType().equals(ROOT)) {
      return ALL_METALAKES;
    }

    MetadataObject.Type type = MetadataObject.Type.valueOf(securableObjectPO.getType());
    if (type == MetadataObject.Type.METALAKE) {
      MetalakePO metalakePO =
          MetalakeMetaService.getInstance().getMetalakePOById(securableObjectPO.getEntityId());
      if (metalakePO == null) {
        return null;
      }

      return metalakePO.getMetalakeName();
    }

    if (type == MetadataObject.Type.CATALOG) {
      return getCatalogFullName(securableObjectPO.getEntityId());
    }

    if (type == MetadataObject.Type.SCHEMA) {
      return getSchemaFullName(securableObjectPO.getEntityId());
    }

    if (type == MetadataObject.Type.TABLE) {
      TablePO tablePO =
          TableMetaService.getInstance().getTablePOById(securableObjectPO.getEntityId());
      if (tablePO == null) {
        return null;
      }
      return getSchemaFullName(tablePO.getSchemaId()) + "." + tablePO.getTableName();
    }

    if (type == MetadataObject.Type.TOPIC) {
      TopicPO topicPO =
          TopicMetaService.getInstance().getTopicPOById(securableObjectPO.getEntityId());
      if (topicPO == null) {
        return null;
      }
      return getSchemaFullName(topicPO.getSchemaId()) + "." + topicPO.getTopicName();
    }

    if (type == MetadataObject.Type.FILESET) {
      FilesetPO filesetPO =
          FilesetMetaService.getInstance().getFilesetPOById(securableObjectPO.getEntityId());
      if (filesetPO == null) {
        return null;
      }
      return getSchemaFullName(filesetPO.getSchemaId()) + "." + filesetPO.getFilesetName();
    }

    throw new IllegalArgumentException(String.format("Doesn't support the type %s", type));
  }

  private String getCatalogFullName(Long entityId) {
    CatalogPO catalogPO = CatalogMetaService.getInstance().getCatalogPOById(entityId);
    if (catalogPO == null) {
      return null;
    }
    return catalogPO.getCatalogName();
  }

  private String getSchemaFullName(Long entityId) {
    SchemaPO schemaPO = SchemaMetaService.getInstance().getSchemaPOById(entityId);

    if (schemaPO == null) {
      return null;
    }

    String catalogName = getCatalogFullName(schemaPO.getCatalogId());
    return catalogName + "." + schemaPO.getSchemaName();
  }

  private MetadataObject.Type getType(String type) {
    if (ROOT.equals(type)) {
      return MetadataObject.Type.METALAKE;
    }
    return MetadataObject.Type.valueOf(type);
  }

  private String getEntityType(SecurableObject securableObject) {
    if (securableObject.type() == MetadataObject.Type.METALAKE
        && securableObject.name().equals(ALL_METALAKES)) {
      return ROOT;
    }
    return securableObject.type().name();
  }
}
