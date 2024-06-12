/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog;

import static com.datastrato.gravitino.Entity.EntityType.TOPIC;
import static com.datastrato.gravitino.StringIdentifier.fromProperties;
import static com.datastrato.gravitino.catalog.PropertiesMetadataHelpers.validatePropertyForCreate;

import com.datastrato.gravitino.EntityStore;
import com.datastrato.gravitino.GravitinoEnv;
import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.datastrato.gravitino.StringIdentifier;
import com.datastrato.gravitino.connector.HasPropertyMetadata;
import com.datastrato.gravitino.connector.capability.Capability;
import com.datastrato.gravitino.exceptions.NoSuchEntityException;
import com.datastrato.gravitino.exceptions.NoSuchSchemaException;
import com.datastrato.gravitino.exceptions.NoSuchTopicException;
import com.datastrato.gravitino.exceptions.TopicAlreadyExistsException;
import com.datastrato.gravitino.lock.LockType;
import com.datastrato.gravitino.lock.TreeLockUtils;
import com.datastrato.gravitino.messaging.DataLayout;
import com.datastrato.gravitino.messaging.Topic;
import com.datastrato.gravitino.messaging.TopicChange;
import com.datastrato.gravitino.meta.AuditInfo;
import com.datastrato.gravitino.meta.TopicEntity;
import com.datastrato.gravitino.storage.IdGenerator;
import com.datastrato.gravitino.utils.PrincipalUtils;
import java.time.Instant;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopicOperationDispatcher extends OperationDispatcher implements TopicDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(TopicOperationDispatcher.class);

  /**
   * Creates a new TopicOperationDispatcher instance.
   *
   * @param catalogManager The CatalogManager instance to be used for catalog operations.
   * @param store The EntityStore instance to be used for catalog operations.
   * @param idGenerator The IdGenerator instance to be used for catalog operations.
   */
  public TopicOperationDispatcher(
      CatalogManager catalogManager, EntityStore store, IdGenerator idGenerator) {
    super(catalogManager, store, idGenerator);
  }

  /**
   * List the topics in a schema namespace from the catalog.
   *
   * @param namespace A schema namespace.
   * @return An array of topic identifiers in the namespace.
   * @throws NoSuchSchemaException If the schema does not exist.
   */
  @Override
  public NameIdentifier[] listTopics(Namespace namespace) throws NoSuchSchemaException {
    return doWithCatalog(
        getCatalogIdentifier(NameIdentifier.of(namespace.levels())),
        c -> c.doWithTopicOps(t -> t.listTopics(namespace)),
        NoSuchSchemaException.class);
  }

  /**
   * Load topic metadata by {@link NameIdentifier} from the catalog.
   *
   * @param ident A topic identifier.
   * @return The topic metadata.
   * @throws NoSuchTopicException If the topic does not exist.
   */
  @Override
  public Topic loadTopic(NameIdentifier ident) throws NoSuchTopicException {
    EntityCombinedTopic topic =
        TreeLockUtils.doWithTreeLock(ident, LockType.READ, () -> loadCombinedTopic(ident));

    if (topic.imported()) {
      return topic;
    }

    if (GravitinoEnv.getInstance()
        .schemaDispatcher()
        .schemaExists(NameIdentifier.of(ident.namespace().levels()))) {
      boolean imported =
          TreeLockUtils.doWithTreeLock(
              NameIdentifier.of(ident.namespace().levels()),
              LockType.WRITE,
              () -> importTopic(ident));
      if (imported) {
        LOG.info("Gravitino imports the topic {} successfully", ident);
      }
    }
    return topic;
  }

  /**
   * Create a topic in the catalog.
   *
   * @param ident A topic identifier.
   * @param comment The comment of the topic object. Null is set if no comment is specified.
   * @param dataLayout The message schema of the topic object. Always null because it's not
   *     supported yet.
   * @param properties The properties of the topic object. Empty map is set if no properties are
   *     specified.
   * @return The topic metadata.
   * @throws NoSuchSchemaException If the schema does not exist.
   * @throws TopicAlreadyExistsException If the topic already exists.
   */
  @Override
  public Topic createTopic(
      NameIdentifier ident, String comment, DataLayout dataLayout, Map<String, String> properties)
      throws NoSuchSchemaException, TopicAlreadyExistsException {
    NameIdentifier catalogIdent = getCatalogIdentifier(ident);
    doWithCatalog(
        catalogIdent,
        c ->
            c.doWithPropertiesMeta(
                p -> {
                  validatePropertyForCreate(p.topicPropertiesMetadata(), properties);
                  return null;
                }),
        IllegalArgumentException.class);
    Long uid = idGenerator.nextId();
    StringIdentifier stringId = StringIdentifier.fromId(uid);
    Map<String, String> updatedProperties =
        StringIdentifier.newPropertiesWithId(stringId, properties);

    doWithCatalog(
        catalogIdent,
        c -> c.doWithTopicOps(t -> t.createTopic(ident, comment, dataLayout, updatedProperties)),
        NoSuchSchemaException.class,
        TopicAlreadyExistsException.class);

    // Retrieve the Topic again to obtain some values generated by underlying catalog
    Topic topic =
        doWithCatalog(
            catalogIdent,
            c -> c.doWithTopicOps(t -> t.loadTopic(ident)),
            NoSuchTopicException.class);

    TopicEntity topicEntity =
        TopicEntity.builder()
            .withId(fromProperties(topic.properties()).id())
            .withName(ident.name())
            .withComment(comment)
            .withNamespace(ident.namespace())
            .withAuditInfo(
                AuditInfo.builder()
                    .withCreator(PrincipalUtils.getCurrentPrincipal().getName())
                    .withCreateTime(Instant.now())
                    .build())
            .build();

    try {
      store.put(topicEntity, true /* overwrite */);
    } catch (Exception e) {
      LOG.error(OperationDispatcher.FormattedErrorMessages.STORE_OP_FAILURE, "put", ident, e);
      return EntityCombinedTopic.of(topic)
          .withHiddenPropertiesSet(
              getHiddenPropertyNames(
                  catalogIdent, HasPropertyMetadata::topicPropertiesMetadata, topic.properties()));
    }

    return EntityCombinedTopic.of(topic, topicEntity)
        .withHiddenPropertiesSet(
            getHiddenPropertyNames(
                catalogIdent, HasPropertyMetadata::topicPropertiesMetadata, topic.properties()));
  }

  /**
   * Apply the {@link TopicChange changes} to a topic in the catalog.
   *
   * @param ident A topic identifier.
   * @param changes The changes to apply to the topic.
   * @return The altered topic metadata.
   * @throws NoSuchTopicException If the topic does not exist.
   * @throws IllegalArgumentException If the changes is rejected by the implementation.
   */
  @Override
  public Topic alterTopic(NameIdentifier ident, TopicChange... changes)
      throws NoSuchTopicException, IllegalArgumentException {
    validateAlterProperties(ident, HasPropertyMetadata::topicPropertiesMetadata, changes);

    NameIdentifier catalogIdent = getCatalogIdentifier(ident);
    Topic tempAlteredTopic =
        doWithCatalog(
            catalogIdent,
            c -> c.doWithTopicOps(t -> t.alterTopic(ident, changes)),
            NoSuchTopicException.class,
            IllegalArgumentException.class);

    // Retrieve the Topic again to obtain some values generated by underlying catalog
    Topic alteredTopic =
        doWithCatalog(
            catalogIdent,
            c ->
                c.doWithTopicOps(
                    t ->
                        t.loadTopic(NameIdentifier.of(ident.namespace(), tempAlteredTopic.name()))),
            NoSuchTopicException.class);

    TopicEntity updatedTopicEntity =
        operateOnEntity(
            ident,
            id ->
                store.update(
                    id,
                    TopicEntity.class,
                    TOPIC,
                    topicEntity ->
                        TopicEntity.builder()
                            .withId(topicEntity.id())
                            .withName(topicEntity.name())
                            .withNamespace(ident.namespace())
                            .withComment(
                                StringUtils.isBlank(tempAlteredTopic.comment())
                                    ? topicEntity.comment()
                                    : tempAlteredTopic.comment())
                            .withAuditInfo(
                                AuditInfo.builder()
                                    .withCreator(topicEntity.auditInfo().creator())
                                    .withCreateTime(topicEntity.auditInfo().createTime())
                                    .withLastModifier(
                                        PrincipalUtils.getCurrentPrincipal().getName())
                                    .withLastModifiedTime(Instant.now())
                                    .build())
                            .build()),
            "UPDATE",
            getStringIdFromProperties(alteredTopic.properties()).id());

    return EntityCombinedTopic.of(alteredTopic, updatedTopicEntity)
        .withHiddenPropertiesSet(
            getHiddenPropertyNames(
                catalogIdent,
                HasPropertyMetadata::topicPropertiesMetadata,
                alteredTopic.properties()));
  }

  /**
   * Drop a topic from the catalog.
   *
   * @param ident A topic identifier.
   * @return true If the topic is dropped, false if the topic does not exist.
   * @throws RuntimeException If an error occurs while dropping the topic.
   */
  @Override
  public boolean dropTopic(NameIdentifier ident) {
    NameIdentifier catalogIdent = getCatalogIdentifier(ident);
    boolean droppedFromCatalog =
        doWithCatalog(
            catalogIdent, c -> c.doWithTopicOps(t -> t.dropTopic(ident)), RuntimeException.class);

    // For unmanaged topic, it could happen that the topic:
    // 1. Is not found in the catalog (dropped directly from underlying sources)
    // 2. Is found in the catalog but not in the store (not managed by Gravitino)
    // 3. Is found in the catalog and the store (managed by Gravitino)
    // 4. Neither found in the catalog nor in the store.
    // In all situations, we try to delete the schema from the store, but we don't take the
    // return value of the store operation into account. We only take the return value of the
    // catalog into account.
    //
    // For managed topic, we should take the return value of the store operation into account.
    boolean droppedFromStore = false;
    try {
      droppedFromStore = store.delete(ident, TOPIC);
    } catch (NoSuchEntityException e) {
      LOG.warn("The topic to be dropped does not exist in the store: {}", ident, e);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return isManagedEntity(catalogIdent, Capability.Scope.TOPIC)
        ? droppedFromStore
        : droppedFromCatalog;
  }

  private boolean importTopic(NameIdentifier identifier) {

    EntityCombinedTopic combinedTopic = loadCombinedTopic(identifier);

    if (combinedTopic.imported()) {
      return false;
    }

    StringIdentifier stringId = null;
    try {
      stringId = combinedTopic.stringIdentifier();
    } catch (IllegalArgumentException ie) {
      LOG.warn(FormattedErrorMessages.STRING_ID_PARSE_ERROR, ie.getMessage());
    }

    long uid;
    if (stringId != null) {
      // If the entity in the store doesn't match the external system, we use the data
      // of external system to correct it.
      uid = stringId.id();
    } else {
      // If entity doesn't exist, we import the entity from the external system.
      uid = idGenerator.nextId();
    }

    TopicEntity topicEntity =
        TopicEntity.builder()
            .withId(uid)
            .withName(combinedTopic.name())
            .withComment(combinedTopic.comment())
            .withNamespace(identifier.namespace())
            .withAuditInfo(
                AuditInfo.builder()
                    .withCreator(combinedTopic.auditInfo().creator())
                    .withCreateTime(combinedTopic.auditInfo().createTime())
                    .withLastModifier(combinedTopic.auditInfo().lastModifier())
                    .withLastModifiedTime(combinedTopic.auditInfo().lastModifiedTime())
                    .build())
            .build();

    try {
      store.put(topicEntity, true);
    } catch (Exception e) {
      LOG.error(FormattedErrorMessages.STORE_OP_FAILURE, "put", identifier, e);
      throw new RuntimeException("Fail to access underlying storage");
    }

    return true;
  }

  private EntityCombinedTopic loadCombinedTopic(NameIdentifier ident) {
    NameIdentifier catalogIdent = getCatalogIdentifier(ident);
    Topic topic =
        doWithCatalog(
            catalogIdent,
            c -> c.doWithTopicOps(t -> t.loadTopic(ident)),
            NoSuchTopicException.class);

    StringIdentifier stringId = getStringIdFromProperties(topic.properties());
    // Case 1: The topic is not created by Gravitino.
    // Note: for Kafka catalog, stringId will not be null. Because there is no way to store the
    // Gravitino
    // ID in Kafka, therefor we use the topic ID as the Gravitino ID
    if (stringId == null) {
      return EntityCombinedTopic.of(topic)
          .withHiddenPropertiesSet(
              getHiddenPropertyNames(
                  catalogIdent, HasPropertyMetadata::topicPropertiesMetadata, topic.properties()))
          .withImported(isEntityExist(ident, TOPIC));
    }

    TopicEntity topicEntity =
        operateOnEntity(
            ident,
            identifier -> store.get(identifier, TOPIC, TopicEntity.class),
            "GET",
            getStringIdFromProperties(topic.properties()).id());

    boolean imported = topicEntity != null;

    return EntityCombinedTopic.of(topic, topicEntity)
        .withHiddenPropertiesSet(
            getHiddenPropertyNames(
                catalogIdent, HasPropertyMetadata::topicPropertiesMetadata, topic.properties()))
        .withImported(imported);
  }
}
