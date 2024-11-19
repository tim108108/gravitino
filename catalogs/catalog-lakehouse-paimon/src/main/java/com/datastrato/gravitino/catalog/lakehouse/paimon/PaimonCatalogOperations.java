/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.lakehouse.paimon;

import static com.datastrato.gravitino.catalog.lakehouse.paimon.GravitinoPaimonTable.fromPaimonTable;
import static com.datastrato.gravitino.catalog.lakehouse.paimon.PaimonSchema.fromPaimonProperties;
import static com.datastrato.gravitino.catalog.lakehouse.paimon.utils.TableOpsUtils.checkColumnCapability;
import static com.datastrato.gravitino.connector.BaseCatalog.CATALOG_BYPASS_PREFIX;
import static com.datastrato.gravitino.rel.expressions.transforms.Transforms.EMPTY_TRANSFORM;
import static com.datastrato.gravitino.rel.indexes.Indexes.EMPTY_INDEXES;

import com.datastrato.gravitino.NameIdentifier;
import com.datastrato.gravitino.Namespace;
import com.datastrato.gravitino.catalog.lakehouse.paimon.ops.PaimonCatalogOps;
import com.datastrato.gravitino.connector.CatalogInfo;
import com.datastrato.gravitino.connector.CatalogOperations;
import com.datastrato.gravitino.connector.PropertiesMetadata;
import com.datastrato.gravitino.exceptions.NoSuchCatalogException;
import com.datastrato.gravitino.exceptions.NoSuchColumnException;
import com.datastrato.gravitino.exceptions.NoSuchSchemaException;
import com.datastrato.gravitino.exceptions.NoSuchTableException;
import com.datastrato.gravitino.exceptions.NonEmptySchemaException;
import com.datastrato.gravitino.exceptions.SchemaAlreadyExistsException;
import com.datastrato.gravitino.exceptions.TableAlreadyExistsException;
import com.datastrato.gravitino.meta.AuditInfo;
import com.datastrato.gravitino.rel.Column;
import com.datastrato.gravitino.rel.SchemaChange;
import com.datastrato.gravitino.rel.SupportsSchemas;
import com.datastrato.gravitino.rel.TableCatalog;
import com.datastrato.gravitino.rel.TableChange;
import com.datastrato.gravitino.rel.TableChange.RenameTable;
import com.datastrato.gravitino.rel.expressions.NamedReference;
import com.datastrato.gravitino.rel.expressions.distributions.Distribution;
import com.datastrato.gravitino.rel.expressions.distributions.Distributions;
import com.datastrato.gravitino.rel.expressions.sorts.SortOrder;
import com.datastrato.gravitino.rel.expressions.transforms.Transform;
import com.datastrato.gravitino.rel.indexes.Index;
import com.datastrato.gravitino.utils.MapUtils;
import com.datastrato.gravitino.utils.PrincipalUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link CatalogOperations} that represents operations for interacting with the
 * Paimon catalog in Gravitino.
 */
public class PaimonCatalogOperations implements CatalogOperations, SupportsSchemas, TableCatalog {

  public static final Logger LOG = LoggerFactory.getLogger(PaimonCatalogOperations.class);

  @VisibleForTesting public PaimonCatalogOps paimonCatalogOps;
  private PaimonCatalogPropertiesMetadata paimonCatalogPropertiesMetadata;
  private PaimonTablePropertiesMetadata paimonTablePropertiesMetadata;
  private PaimonSchemaPropertiesMetadata paimonSchemaPropertiesMetadata;

  private static final String NO_SUCH_SCHEMA_EXCEPTION =
      "Paimon schema (database) %s does not exist.";
  private static final String NON_EMPTY_SCHEMA_EXCEPTION =
      "Paimon schema (database) %s is not empty. One or more tables exist.";
  private static final String SCHEMA_ALREADY_EXISTS_EXCEPTION =
      "Paimon schema (database) %s already exists.";
  private static final String NO_SUCH_TABLE_EXCEPTION = "Paimon table %s does not exist.";
  private static final String TABLE_ALREADY_EXISTS_EXCEPTION = "Paimon table %s already exists.";
  private static final String NO_SUCH_COLUMN_EXCEPTION =
      "Paimon column of table %s does not exist.";

  /**
   * Initializes the Paimon catalog operations with the provided configuration.
   *
   * @param conf The configuration map for the Paimon catalog operations.
   * @param info The catalog info associated with this operations instance.
   * @throws RuntimeException if initialization fails.
   */
  @Override
  public void initialize(Map<String, String> conf, CatalogInfo info) throws RuntimeException {
    // Key format like gravitino.bypass.a.b
    Map<String, String> prefixMap = MapUtils.getPrefixMap(conf, CATALOG_BYPASS_PREFIX);

    this.paimonCatalogPropertiesMetadata = new PaimonCatalogPropertiesMetadata();
    this.paimonTablePropertiesMetadata = new PaimonTablePropertiesMetadata();
    this.paimonSchemaPropertiesMetadata = new PaimonSchemaPropertiesMetadata();

    // Hold keys that lie in GRAVITINO_CONFIG_TO_PAIMON
    Map<String, String> gravitinoConfig =
        this.paimonCatalogPropertiesMetadata.transformProperties(conf);

    Map<String, String> resultConf = Maps.newHashMap(prefixMap);
    resultConf.putAll(gravitinoConfig);

    this.paimonCatalogOps = new PaimonCatalogOps(new PaimonConfig(resultConf));
  }

  /**
   * Lists the schemas under the specified namespace.
   *
   * @param namespace The namespace to list the schemas for.
   * @return An array of {@link NameIdentifier} representing the schemas in the namespace.
   * @throws NoSuchCatalogException If the provided namespace is invalid or does not exist.
   */
  @Override
  public NameIdentifier[] listSchemas(Namespace namespace) throws NoSuchCatalogException {
    return paimonCatalogOps.listDatabases().stream()
        .map(paimonNamespace -> NameIdentifier.of(namespace, paimonNamespace))
        .toArray(NameIdentifier[]::new);
  }

  /**
   * Creates a new schema with the provided identifier, comment, and metadata.
   *
   * @param identifier The identifier of the schema to create.
   * @param comment The comment for the new schema.
   * @param properties The properties for the new schema.
   * @return The newly created {@link PaimonSchema} instance.
   * @throws NoSuchCatalogException If the provided namespace is invalid or does not exist.
   * @throws SchemaAlreadyExistsException If a schema with the same name already exists.
   */
  @Override
  public PaimonSchema createSchema(
      NameIdentifier identifier, String comment, Map<String, String> properties)
      throws NoSuchCatalogException, SchemaAlreadyExistsException {
    String currentUser = currentUser();
    PaimonSchema createdSchema =
        PaimonSchema.builder()
            .withName(identifier.name())
            .withComment(comment)
            .withProperties(properties)
            .withAuditInfo(
                AuditInfo.builder().withCreator(currentUser).withCreateTime(Instant.now()).build())
            .build();
    try {
      Map<String, String> paimonSchemaProperties = createdSchema.toPaimonProperties();
      paimonCatalogOps.createDatabase(identifier.name(), paimonSchemaProperties);
    } catch (Catalog.DatabaseAlreadyExistException e) {
      throw new SchemaAlreadyExistsException(e, SCHEMA_ALREADY_EXISTS_EXCEPTION, identifier);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    LOG.info(
        "Created Paimon schema (database): {}. Current user: {}. Comment: {}. Metadata: {}.",
        identifier,
        currentUser,
        comment,
        properties);
    return createdSchema;
  }

  /**
   * Loads the schema with the provided identifier.
   *
   * @param identifier The identifier of the schema to load.
   * @return The loaded {@link PaimonSchema} representing the schema.
   * @throws NoSuchSchemaException If the schema with the provided identifier does not exist.
   */
  @Override
  public PaimonSchema loadSchema(NameIdentifier identifier) throws NoSuchSchemaException {
    Map<String, String> properties;
    try {
      properties = paimonCatalogOps.loadDatabase(identifier.name());
    } catch (Catalog.DatabaseNotExistException e) {
      throw new NoSuchSchemaException(e, NO_SUCH_SCHEMA_EXCEPTION, identifier);
    }
    LOG.info("Loaded Paimon schema (database) {}.", identifier);
    return fromPaimonProperties(identifier.name(), properties);
  }

  /**
   * Alters the schema with the provided identifier according to the specified {@link SchemaChange}
   * changes.
   *
   * @param identifier The identifier of the schema to alter.
   * @param changes The changes to apply to the schema.
   * @return The altered {@link PaimonSchema} instance.
   * @throws NoSuchSchemaException If the schema with the provided identifier does not exist.
   */
  @Override
  public PaimonSchema alterSchema(NameIdentifier identifier, SchemaChange... changes)
      throws NoSuchSchemaException {
    throw new UnsupportedOperationException("AlterSchema is unsupported now for Paimon Catalog.");
  }

  /**
   * Drops the schema with the provided identifier.
   *
   * @param identifier The identifier of the schema to drop.
   * @param cascade If set to true, drops all the tables in the schema as well.
   * @return true if the schema is dropped successfully, false otherwise.
   * @throws NonEmptySchemaException If the schema is not empty and 'cascade' is set to false.
   */
  @Override
  public boolean dropSchema(NameIdentifier identifier, boolean cascade)
      throws NonEmptySchemaException {
    try {
      paimonCatalogOps.dropDatabase(identifier.name(), cascade);
    } catch (Catalog.DatabaseNotExistException e) {
      LOG.warn("Paimon schema (database) {} does not exist.", identifier);
      return false;
    } catch (Catalog.DatabaseNotEmptyException e) {
      throw new NonEmptySchemaException(e, NON_EMPTY_SCHEMA_EXCEPTION, identifier);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    LOG.info("Dropped Paimon schema (database) {}.", identifier);
    return true;
  }

  /**
   * Lists all the tables under the specified namespace.
   *
   * @param namespace The namespace to list tables for.
   * @return An array of {@link NameIdentifier} representing the tables in the namespace.
   * @throws NoSuchSchemaException If the schema with the provided namespace does not exist.
   */
  @Override
  public NameIdentifier[] listTables(Namespace namespace) throws NoSuchSchemaException {
    String[] levels = namespace.levels();
    NameIdentifier schemaIdentifier = NameIdentifier.of(levels[levels.length - 1]);
    if (!schemaExists(schemaIdentifier)) {
      throw new NoSuchSchemaException(NO_SUCH_SCHEMA_EXCEPTION, namespace.toString());
    }
    List<String> tables;
    try {
      tables = paimonCatalogOps.listTables(schemaIdentifier.name());
    } catch (Catalog.DatabaseNotExistException e) {
      throw new NoSuchSchemaException(NO_SUCH_SCHEMA_EXCEPTION, namespace.toString());
    }
    return tables.stream()
        .map(
            tableIdentifier ->
                NameIdentifier.of(ArrayUtils.add(namespace.levels(), tableIdentifier)))
        .toArray(NameIdentifier[]::new);
  }

  /**
   * Loads the table with the provided identifier.
   *
   * @param identifier The identifier of the table to load.
   * @return The loaded {@link GravitinoPaimonTable} instance representing the table.
   * @throws NoSuchTableException If the table with the provided identifier does not exist.
   */
  @Override
  public GravitinoPaimonTable loadTable(NameIdentifier identifier) throws NoSuchTableException {
    Table table;
    try {
      NameIdentifier tableIdentifier = buildPaimonNameIdentifier(identifier);
      table = paimonCatalogOps.loadTable(tableIdentifier.toString());
    } catch (Catalog.TableNotExistException e) {
      throw new NoSuchTableException(e, NO_SUCH_TABLE_EXCEPTION, identifier);
    }
    LOG.info("Loaded Paimon table {}.", identifier);
    return fromPaimonTable(table);
  }

  /**
   * Creates a new table with the provided identifier, comment, and metadata.
   *
   * @param identifier The identifier of the table to create.
   * @param columns The array of columns for the new table.
   * @param comment The comment for the new table.
   * @param properties The properties for the new table.
   * @param partitioning The partitioning for the new table.
   * @param indexes The indexes for the new table.
   * @return The newly created {@link GravitinoPaimonTable} instance.
   * @throws NoSuchSchemaException If the schema with the provided namespace does not exist.
   * @throws TableAlreadyExistsException If the table with the same identifier already exists.
   */
  @Override
  public GravitinoPaimonTable createTable(
      NameIdentifier identifier,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitioning,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes)
      throws NoSuchSchemaException, TableAlreadyExistsException {
    NameIdentifier nameIdentifier = buildPaimonNameIdentifier(identifier);
    NameIdentifier schemaIdentifier = NameIdentifier.of(nameIdentifier.namespace().levels());
    if (!schemaExists(schemaIdentifier)) {
      throw new NoSuchSchemaException(NO_SUCH_SCHEMA_EXCEPTION, schemaIdentifier);
    }
    if (partitioning == null) {
      partitioning = EMPTY_TRANSFORM;
    }
    if (indexes == null) {
      indexes = EMPTY_INDEXES;
    }
    Preconditions.checkArgument(
        Arrays.stream(partitioning)
            .allMatch(
                partition -> {
                  NamedReference[] references = partition.references();
                  return references.length == 1
                      && references[0] instanceof NamedReference.FieldReference;
                }),
        "Paimon only support identity partition.");
    Preconditions.checkArgument(
        distribution == null || distribution.strategy() == Distributions.NONE.strategy(),
        "Distribution is not supported for Paimon in Gravitino now.");
    Preconditions.checkArgument(
        sortOrders == null || sortOrders.length == 0,
        "Sort orders are not supported for Paimon in Gravitino.");
    checkPaimonIndexes(indexes);
    String currentUser = currentUser();
    GravitinoPaimonTable createdTable =
        GravitinoPaimonTable.builder()
            .withName(identifier.name())
            .withColumns(
                Arrays.stream(columns)
                    .map(
                        column -> {
                          checkColumnCapability(
                              column.name(), column.defaultValue(), column.autoIncrement());
                          return GravitinoPaimonColumn.builder()
                              .withName(column.name())
                              .withType(column.dataType())
                              .withComment(column.comment())
                              .withNullable(column.nullable())
                              .withAutoIncrement(column.autoIncrement())
                              .withDefaultValue(column.defaultValue())
                              .build();
                        })
                    .toArray(GravitinoPaimonColumn[]::new))
            .withPartitioning(partitioning)
            .withComment(comment)
            .withProperties(properties)
            .withIndexes(indexes)
            .withAuditInfo(
                AuditInfo.builder().withCreator(currentUser).withCreateTime(Instant.now()).build())
            .build();
    try {
      Schema paimonTableSchema = createdTable.toPaimonTableSchema();
      paimonCatalogOps.createTable(nameIdentifier.toString(), paimonTableSchema);
    } catch (Catalog.DatabaseNotExistException e) {
      throw new NoSuchSchemaException(e, NO_SUCH_SCHEMA_EXCEPTION, identifier);
    } catch (Catalog.TableAlreadyExistException e) {
      throw new TableAlreadyExistsException(e, TABLE_ALREADY_EXISTS_EXCEPTION, identifier);
    }
    LOG.info(
        "Created Paimon table: {}. Current user: {}. Comment: {}. Metadata: {}.",
        identifier,
        currentUser,
        comment,
        properties);
    return createdTable;
  }

  /**
   * Alters the table with the provided identifier according to the specified {@link TableChange}
   * changes.
   *
   * @param identifier The identifier of the table to alter.
   * @param changes The changes to apply to the table.
   * @return The altered {@link GravitinoPaimonTable} instance.
   * @throws NoSuchTableException If the table with the provided identifier does not exist.
   * @throws IllegalArgumentException This exception will not be thrown in this method.
   */
  @Override
  public GravitinoPaimonTable alterTable(NameIdentifier identifier, TableChange... changes)
      throws NoSuchTableException, IllegalArgumentException {
    Optional<TableChange> renameTableOpt =
        Arrays.stream(changes)
            .filter(tableChange -> tableChange instanceof RenameTable)
            .reduce((a, b) -> b);
    if (renameTableOpt.isPresent()) {
      String otherChanges =
          Arrays.stream(changes)
              .filter(tableChange -> !(tableChange instanceof RenameTable))
              .map(String::valueOf)
              .collect(Collectors.joining("\n"));
      Preconditions.checkArgument(
          StringUtils.isEmpty(otherChanges),
          String.format(
              "The operation to change the table name cannot be performed together with other operations. "
                  + "The list of operations that you cannot perform includes: \n%s",
              otherChanges));
      return renameTable(identifier, (RenameTable) renameTableOpt.get());
    }
    return internalAlterTable(identifier, changes);
  }

  /**
   * Drops the table with the provided identifier.
   *
   * @param identifier The identifier of the table to drop.
   * @return true if the table is successfully dropped, false if the table does not exist.
   */
  @Override
  public boolean dropTable(NameIdentifier identifier) {
    try {
      NameIdentifier tableIdentifier = buildPaimonNameIdentifier(identifier);
      paimonCatalogOps.dropTable(tableIdentifier.toString());
    } catch (Catalog.TableNotExistException e) {
      LOG.warn("Paimon table {} does not exist.", identifier);
      return false;
    }
    LOG.info("Dropped Paimon table {}.", identifier);
    return true;
  }

  /**
   * Purges the table with the provided identifier.
   *
   * @param identifier The identifier of the table to purge.
   * @return true if the table is successfully purged, false if the table does not exist.
   * @throws UnsupportedOperationException If the table type is EXTERNAL_TABLE, it cannot be purged.
   */
  @Override
  public boolean purgeTable(NameIdentifier identifier) throws UnsupportedOperationException {
    throw new UnsupportedOperationException("purgeTable is unsupported now for Paimon Catalog.");
  }

  @Override
  public void close() {
    if (paimonCatalogOps != null) {
      try {
        paimonCatalogOps.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public PropertiesMetadata tablePropertiesMetadata() throws UnsupportedOperationException {
    return paimonTablePropertiesMetadata;
  }

  @Override
  public PropertiesMetadata catalogPropertiesMetadata() throws UnsupportedOperationException {
    return paimonCatalogPropertiesMetadata;
  }

  @Override
  public PropertiesMetadata schemaPropertiesMetadata() throws UnsupportedOperationException {
    return paimonSchemaPropertiesMetadata;
  }

  @Override
  public PropertiesMetadata filesetPropertiesMetadata() throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Paimon catalog doesn't support fileset related operations");
  }

  @Override
  public PropertiesMetadata topicPropertiesMetadata() throws UnsupportedOperationException {
    throw new UnsupportedOperationException(
        "Paimon catalog doesn't support topic related operations");
  }

  private static String currentUser() {
    return PrincipalUtils.getCurrentUserName();
  }

  private NameIdentifier buildPaimonNameIdentifier(NameIdentifier identifier) {
    Preconditions.checkArgument(
        identifier != null
            && identifier.namespace() != null
            && identifier.namespace().levels().length > 0,
        "Namespace can not be null or empty.");
    String[] levels = identifier.namespace().levels();
    return NameIdentifier.of(levels[levels.length - 1], identifier.name());
  }

  private void checkPaimonIndexes(Index[] indexes) {
    Preconditions.checkArgument(indexes.length <= 1, "Paimon supports no more than one Index.");
    Arrays.stream(indexes)
        .forEach(
            index ->
                Preconditions.checkArgument(
                    index.type() == Index.IndexType.PRIMARY_KEY,
                    "Paimon only supports primary key Index."));
  }

  /**
   * Performs rename table change with the provided identifier.
   *
   * @param identifier The identifier of the table to rename.
   * @param renameTable Table Change to modify the table name.
   * @return The renamed {@link GravitinoPaimonTable} instance.
   * @throws NoSuchTableException If the table with the provided identifier does not exist.
   * @throws IllegalArgumentException This exception will not be thrown in this method.
   */
  private GravitinoPaimonTable renameTable(
      NameIdentifier identifier, TableChange.RenameTable renameTable)
      throws NoSuchTableException, IllegalArgumentException {
    NameIdentifier newNnameIdentifier =
        NameIdentifier.of(identifier.namespace(), renameTable.getNewName());
    NameIdentifier oldIdentifier = buildPaimonNameIdentifier(identifier);
    NameIdentifier newIdentifier = buildPaimonNameIdentifier(newNnameIdentifier);
    try {
      paimonCatalogOps.renameTable(oldIdentifier.toString(), newIdentifier.toString());
    } catch (Catalog.TableNotExistException e) {
      throw new NoSuchTableException(e, NO_SUCH_TABLE_EXCEPTION, oldIdentifier);
    } catch (Catalog.TableAlreadyExistException e) {
      throw new TableAlreadyExistsException(e, TABLE_ALREADY_EXISTS_EXCEPTION, newIdentifier);
    }
    return loadTable(newNnameIdentifier);
  }

  /**
   * Performs alter table changes with the provided identifier according to the specified {@link
   * TableChange} changes.
   *
   * @param identifier The identifier of the table to alter.
   * @param changes The changes to apply to the table.
   * @return The altered {@link GravitinoPaimonTable} instance.
   * @throws NoSuchTableException If the table with the provided identifier does not exist.
   * @throws IllegalArgumentException This exception will not be thrown in this method.
   */
  private GravitinoPaimonTable internalAlterTable(NameIdentifier identifier, TableChange... changes)
      throws NoSuchTableException, IllegalArgumentException {
    NameIdentifier paimonNameIdentifier = buildPaimonNameIdentifier(identifier);
    try {
      paimonCatalogOps.alterTable(paimonNameIdentifier.toString(), changes);
    } catch (Catalog.TableNotExistException e) {
      throw new NoSuchTableException(e, NO_SUCH_TABLE_EXCEPTION, paimonNameIdentifier);
    } catch (Catalog.ColumnNotExistException e) {
      throw new NoSuchColumnException(e, NO_SUCH_COLUMN_EXCEPTION, paimonNameIdentifier);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return loadTable(identifier);
  }
}
