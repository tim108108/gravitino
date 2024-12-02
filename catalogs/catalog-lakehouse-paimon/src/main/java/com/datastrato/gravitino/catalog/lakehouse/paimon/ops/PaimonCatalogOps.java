/*
 * Copyright 2024 Datastrato Pvt Ltd.
 * This software is licensed under the Apache License version 2.
 */
package com.datastrato.gravitino.catalog.lakehouse.paimon.ops;

import static com.datastrato.gravitino.catalog.lakehouse.paimon.utils.CatalogUtils.loadCatalogBackend;
import static com.datastrato.gravitino.catalog.lakehouse.paimon.utils.TableOpsUtils.buildSchemaChanges;

import com.datastrato.gravitino.catalog.lakehouse.paimon.PaimonConfig;
import com.datastrato.gravitino.rel.TableChange;
import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Map;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Catalog.DatabaseAlreadyExistException;
import org.apache.paimon.catalog.Catalog.DatabaseNotEmptyException;
import org.apache.paimon.catalog.Catalog.DatabaseNotExistException;
import org.apache.paimon.catalog.Catalog.TableNotExistException;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.schema.Schema;
import org.apache.paimon.table.Table;

/** Table operation proxy that handles table operations of an underlying Paimon catalog. */
public class PaimonCatalogOps implements AutoCloseable {

  private final PaimonBackendCatalogWrapper paimonBackendCatalogWrapper;
  protected Catalog catalog;

  public PaimonCatalogOps(PaimonConfig paimonConfig) {
    paimonBackendCatalogWrapper = loadCatalogBackend(paimonConfig);
    Preconditions.checkArgument(
        paimonBackendCatalogWrapper.getCatalog() != null,
        "Can not load Paimon backend catalog instance.");
    catalog = paimonBackendCatalogWrapper.getCatalog();
  }

  @Override
  public void close() throws Exception {
    if (paimonBackendCatalogWrapper != null) {
      paimonBackendCatalogWrapper.close();
    }
  }

  public List<String> listDatabases() {
    return catalog.listDatabases();
  }

  public Map<String, String> loadDatabase(String databaseName) throws DatabaseNotExistException {
    return catalog.loadDatabaseProperties(databaseName);
  }

  public void createDatabase(String databaseName, Map<String, String> properties)
      throws DatabaseAlreadyExistException {
    catalog.createDatabase(databaseName, false, properties);
  }

  public void dropDatabase(String databaseName, boolean cascade)
      throws DatabaseNotExistException, DatabaseNotEmptyException {
    catalog.dropDatabase(databaseName, false, cascade);
  }

  public List<String> listTables(String databaseName) throws DatabaseNotExistException {
    return catalog.listTables(databaseName);
  }

  public Table loadTable(String tableName) throws TableNotExistException {
    return catalog.getTable(tableIdentifier(tableName));
  }

  public void createTable(String tableName, Schema schema)
      throws Catalog.TableAlreadyExistException, DatabaseNotExistException {
    catalog.createTable(tableIdentifier(tableName), schema, false);
  }

  public void dropTable(String tableName) throws TableNotExistException {
    catalog.dropTable(tableIdentifier(tableName), false);
  }

  public void alterTable(String tableName, TableChange... changes)
      throws Catalog.ColumnAlreadyExistException, TableNotExistException,
          Catalog.ColumnNotExistException {
    catalog.alterTable(tableIdentifier(tableName), buildSchemaChanges(changes), false);
  }

  public void renameTable(String fromTableName, String toTableName)
      throws TableNotExistException, Catalog.TableAlreadyExistException {
    catalog.renameTable(tableIdentifier(fromTableName), tableIdentifier(toTableName), false);
  }

  private Identifier tableIdentifier(String tableName) {
    return Identifier.fromString(tableName);
  }
}