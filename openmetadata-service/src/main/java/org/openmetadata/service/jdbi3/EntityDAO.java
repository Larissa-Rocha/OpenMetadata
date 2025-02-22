/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.jdbi3;

import static org.openmetadata.service.exception.CatalogExceptionMessage.entityNotFound;
import static org.openmetadata.service.jdbi3.ListFilter.escape;
import static org.openmetadata.service.jdbi3.ListFilter.escapeApostrophe;
import static org.openmetadata.service.jdbi3.locator.ConnectionType.MYSQL;
import static org.openmetadata.service.jdbi3.locator.ConnectionType.POSTGRES;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.SneakyThrows;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.CatalogExceptionMessage;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.locator.ConnectionAwareSqlQuery;
import org.openmetadata.service.jdbi3.locator.ConnectionAwareSqlUpdate;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.jdbi.BindFQN;

public interface EntityDAO<T extends EntityInterface> {
  org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EntityDAO.class);

  /** Methods that need to be overridden by interfaces extending this */
  String getTableName();

  Class<T> getEntityClass();

  default String getNameColumn() {
    return "name";
  }

  default String getNameHashColumn() {
    return "nameHash";
  }

  default boolean supportsSoftDelete() {
    return true;
  }

  /** Common queries for all entities implemented here. Do not override. */
  @ConnectionAwareSqlUpdate(
      value = "INSERT INTO <table> (<nameHashColumn>, json) VALUES (:nameHashColumnValue, :json)",
      connectionType = MYSQL)
  @ConnectionAwareSqlUpdate(
      value = "INSERT INTO <table> (<nameHashColumn>, json) VALUES (:nameHashColumnValue, :json :: jsonb)",
      connectionType = POSTGRES)
  int insert(
      @Define("table") String table,
      @Define("nameHashColumn") String nameHashColumn,
      @BindFQN("nameHashColumnValue") String nameHashColumnValue,
      @Bind("json") String json);

  @ConnectionAwareSqlUpdate(
      value = "UPDATE <table> SET  json = :json, <nameHashColumn> = :nameHashColumnValue WHERE id = :id",
      connectionType = MYSQL)
  @ConnectionAwareSqlUpdate(
      value = "UPDATE <table> SET  json = (:json :: jsonb), <nameHashColumn> = :nameHashColumnValue WHERE id = :id",
      connectionType = POSTGRES)
  void update(
      @Define("table") String table,
      @Define("nameHashColumn") String nameHashColumn,
      @BindFQN("nameHashColumnValue") String nameHashColumnValue,
      @Bind("id") String id,
      @Bind("json") String json);

  default void updateFqn(String oldPrefix, String newPrefix) {
    LOG.info("Updating FQN for {} from {} to {}", getTableName(), oldPrefix, newPrefix);
    if (!getNameHashColumn().equals("fqnHash")) {
      return;
    }
    String mySqlUpdate =
        String.format(
            "UPDATE %s SET json = "
                + "JSON_REPLACE(json, '$.fullyQualifiedName', REGEXP_REPLACE(JSON_UNQUOTE(JSON_EXTRACT(json, '$.fullyQualifiedName')), '^%s\\.', '%s.')) "
                + ", fqnHash = REPLACE(fqnHash, '%s.', '%s.') "
                + "WHERE fqnHash LIKE '%s.%%'",
            getTableName(),
            escape(oldPrefix),
            escapeApostrophe(newPrefix),
            FullyQualifiedName.buildHash(oldPrefix),
            FullyQualifiedName.buildHash(newPrefix),
            FullyQualifiedName.buildHash(oldPrefix));

    String postgresUpdate =
        String.format(
            "UPDATE %s SET json = "
                + "REPLACE(json::text, '\"fullyQualifiedName\": \"%s.', "
                + "'\"fullyQualifiedName\": \"%s.')::jsonb "
                + ", fqnHash = REPLACE(fqnHash, '%s.', '%s.') "
                + "WHERE fqnHash LIKE '%s.%%'",
            getTableName(),
            escapeApostrophe(oldPrefix),
            escapeApostrophe(newPrefix),
            FullyQualifiedName.buildHash(oldPrefix),
            FullyQualifiedName.buildHash(newPrefix),
            FullyQualifiedName.buildHash(oldPrefix));
    updateFqnInternal(mySqlUpdate, postgresUpdate);
  }

  @ConnectionAwareSqlUpdate(value = "<mySqlUpdate>", connectionType = MYSQL)
  @ConnectionAwareSqlUpdate(value = "<postgresUpdate>", connectionType = POSTGRES)
  void updateFqnInternal(@Define("mySqlUpdate") String mySqlUpdate, @Define("postgresUpdate") String postgresUpdate);

  @SqlQuery("SELECT json FROM <table> WHERE id = :id <cond>")
  String findById(@Define("table") String table, @Bind("id") String id, @Define("cond") String cond);

  @SqlQuery("SELECT json FROM <table> WHERE <nameColumn> = :name <cond>")
  String findByName(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @BindFQN("name") String name,
      @Define("cond") String cond);

  @SqlQuery("SELECT count(*) FROM <table> <cond>")
  int listCount(@Define("table") String table, @Define("nameColumn") String nameColumn, @Define("cond") String cond);

  @ConnectionAwareSqlQuery(value = "SELECT count(*) FROM <table> <mysqlCond>", connectionType = MYSQL)
  @ConnectionAwareSqlQuery(value = "SELECT count(*) FROM <table> <postgresCond>", connectionType = POSTGRES)
  int listCount(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @Define("mysqlCond") String mysqlCond,
      @Define("postgresCond") String postgresCond);

  @ConnectionAwareSqlQuery(
      value =
          "SELECT json FROM ("
              + "SELECT <table>.<nameColumn>, <table>.json FROM <table> <mysqlCond> AND "
              + "<table>.<nameColumn> < :before "
              + // Pagination by entity fullyQualifiedName or name (when entity does not have fqn)
              "ORDER BY <table>.<nameColumn> DESC "
              + // Pagination ordering by entity fullyQualifiedName or name (when entity does not have fqn)
              "LIMIT :limit"
              + ") last_rows_subquery ORDER BY <nameColumn>",
      connectionType = MYSQL)
  @ConnectionAwareSqlQuery(
      value =
          "SELECT json FROM ("
              + "SELECT <table>.<nameColumn>, <table>.json FROM <table> <postgresCond> AND "
              + "<table>.<nameColumn> < :before "
              + // Pagination by entity fullyQualifiedName or name (when entity does not have fqn)
              "ORDER BY <table>.<nameColumn> DESC "
              + // Pagination ordering by entity fullyQualifiedName or name (when entity does not have fqn)
              "LIMIT :limit"
              + ") last_rows_subquery ORDER BY <nameColumn>",
      connectionType = POSTGRES)
  List<String> listBefore(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @Define("mysqlCond") String mysqlCond,
      @Define("postgresCond") String postgresCond,
      @Bind("limit") int limit,
      @Bind("before") String before);

  @ConnectionAwareSqlQuery(
      value =
          "SELECT <table>.json FROM <table> <mysqlCond> AND "
              + "<table>.<nameColumn> > :after "
              + "ORDER BY <table>.<nameColumn> "
              + "LIMIT :limit",
      connectionType = MYSQL)
  @ConnectionAwareSqlQuery(
      value =
          "SELECT <table>.json FROM <table> <postgresCond> AND "
              + "<table>.<nameColumn> > :after "
              + "ORDER BY <table>.<nameColumn> "
              + "LIMIT :limit",
      connectionType = POSTGRES)
  List<String> listAfter(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @Define("mysqlCond") String mysqlCond,
      @Define("postgresCond") String postgresCond,
      @Bind("limit") int limit,
      @Bind("after") String after);

  @SqlQuery("SELECT count(*) FROM <table>")
  int listTotalCount(@Define("table") String table, @Define("nameColumn") String nameColumn);

  @SqlQuery(
      "SELECT json FROM ("
          + "SELECT <nameColumn>, json FROM <table> <cond> AND "
          + "<nameColumn> < :before "
          + // Pagination by entity fullyQualifiedName or name (when entity does not have fqn)
          "ORDER BY <nameColumn> DESC "
          + // Pagination ordering by entity fullyQualifiedName or name (when entity does not have fqn)
          "LIMIT :limit"
          + ") last_rows_subquery ORDER BY <nameColumn>")
  List<String> listBefore(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @Define("cond") String cond,
      @Bind("limit") int limit,
      @Bind("before") String before);

  @SqlQuery(
      "SELECT json FROM <table> <cond> AND " + "<nameColumn> > :after " + "ORDER BY <nameColumn> " + "LIMIT :limit")
  List<String> listAfter(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @Define("cond") String cond,
      @Bind("limit") int limit,
      @Bind("after") String after);

  @SqlQuery("SELECT json FROM <table> LIMIT :limit OFFSET :offset")
  List<String> listAfterWithOffset(@Define("table") String table, @Bind("limit") int limit, @Bind("offset") int offset);

  @SqlQuery("SELECT json FROM <table> WHERE <nameHashColumn> = '' or <nameHashColumn> is null LIMIT :limit")
  List<String> migrationListAfterWithOffset(
      @Define("table") String table, @Define("nameHashColumn") String nameHashColumnName, @Bind("limit") int limit);

  @SqlQuery("SELECT json FROM <table> <cond> AND " + "ORDER BY <nameColumn> " + "LIMIT :limit " + "OFFSET :offset")
  List<String> listAfter(
      @Define("table") String table,
      @Define("nameColumn") String nameColumn,
      @Define("cond") String cond,
      @Bind("limit") int limit,
      @Bind("offset") int offset);

  @SqlQuery("SELECT EXISTS (SELECT * FROM <table> WHERE id = :id)")
  boolean exists(@Define("table") String table, @Bind("id") String id);

  @SqlQuery("SELECT EXISTS (SELECT * FROM <table> WHERE <nameColumnHash> = :fqnHash)")
  boolean existsByName(
      @Define("table") String table,
      @Define("nameColumnHash") String nameColumnHash,
      @BindFQN("fqnHash") String fqnHash);

  @SqlUpdate("DELETE FROM <table> WHERE id = :id")
  int delete(@Define("table") String table, @Bind("id") String id);

  /** Default methods that interfaces with implementation. Don't override */
  default void insert(EntityInterface entity, String fqn) throws JsonProcessingException {
    insert(getTableName(), getNameHashColumn(), fqn, JsonUtils.pojoToJson(entity));
  }

  default void insert(String nameHash, EntityInterface entity, String fqn) throws JsonProcessingException {
    insert(getTableName(), nameHash, fqn, JsonUtils.pojoToJson(entity));
  }

  default void update(UUID id, String fqn, String json) {
    update(getTableName(), getNameHashColumn(), fqn, id.toString(), json);
  }

  default void update(EntityInterface entity) throws JsonProcessingException {
    update(
        getTableName(),
        getNameHashColumn(),
        entity.getFullyQualifiedName(),
        entity.getId().toString(),
        JsonUtils.pojoToJson(entity));
  }

  default void update(String nameHashColumn, EntityInterface entity) throws JsonProcessingException {
    update(
        getTableName(),
        nameHashColumn,
        entity.getFullyQualifiedName(),
        entity.getId().toString(),
        JsonUtils.pojoToJson(entity));
  }

  default String getCondition(Include include) {
    if (!supportsSoftDelete()) {
      return "";
    }

    if (include == null || include == Include.NON_DELETED) {
      return "AND deleted = FALSE";
    }
    return include == Include.DELETED ? " AND deleted = TRUE" : "";
  }

  default T findEntityById(UUID id, Include include) throws IOException {
    return jsonToEntity(findById(getTableName(), id.toString(), getCondition(include)), id.toString());
  }

  default T findEntityById(UUID id) throws IOException {
    return findEntityById(id, Include.NON_DELETED);
  }

  default T findEntityByName(String fqn) {
    return findEntityByName(fqn, Include.NON_DELETED);
  }

  @SneakyThrows
  default T findEntityByName(String fqn, Include include) {
    return jsonToEntity(findByName(getTableName(), getNameHashColumn(), fqn, getCondition(include)), fqn);
  }

  @SneakyThrows
  default T findEntityByName(String fqn, String nameHashColumn, Include include) {
    return jsonToEntity(findByName(getTableName(), nameHashColumn, fqn, getCondition(include)), fqn);
  }

  default T jsonToEntity(String json, String identity) throws IOException {
    Class<T> clz = getEntityClass();
    T entity = json != null ? JsonUtils.readValue(json, clz) : null;
    if (entity == null) {
      String entityType = Entity.getEntityTypeFromClass(clz);
      throw EntityNotFoundException.byMessage(CatalogExceptionMessage.entityNotFound(entityType, identity));
    }
    return entity;
  }

  default EntityReference findEntityReferenceById(UUID id) throws IOException {
    return findEntityById(id).getEntityReference();
  }

  default EntityReference findEntityReferenceByName(String fqn) {
    return findEntityByName(fqn).getEntityReference();
  }

  default EntityReference findEntityReferenceById(UUID id, Include include) throws IOException {
    return findEntityById(id, include).getEntityReference();
  }

  default EntityReference findEntityReferenceByName(String fqn, Include include) {
    return findEntityByName(fqn, include).getEntityReference();
  }

  default String findJsonById(UUID id, Include include) {
    return findById(getTableName(), id.toString(), getCondition(include));
  }

  default String findJsonByFqn(String fqn, Include include) {
    return findByName(getTableName(), getNameHashColumn(), fqn, getCondition(include));
  }

  default int listCount(ListFilter filter) {
    return listCount(getTableName(), getNameHashColumn(), filter.getCondition());
  }

  default int listTotalCount() {
    return listTotalCount(getTableName(), getNameHashColumn());
  }

  default List<String> listBefore(ListFilter filter, int limit, String before) {
    // Quoted name is stored in fullyQualifiedName column and not in the name column
    before = getNameColumn().equals("name") ? FullyQualifiedName.unquoteName(before) : before;
    return listBefore(getTableName(), getNameColumn(), filter.getCondition(), limit, before);
  }

  default List<String> listAfter(ListFilter filter, int limit, String after) {
    // Quoted name is stored in fullyQualifiedName column and not in the name column
    after = getNameColumn().equals("name") ? FullyQualifiedName.unquoteName(after) : after;
    return listAfter(getTableName(), getNameColumn(), filter.getCondition(), limit, after);
  }

  default List<String> listAfterWithOffset(int limit, int offset) {
    // No ordering
    return listAfterWithOffset(getTableName(), limit, offset);
  }

  default List<String> migrationListAfterWithOffset(int limit, String nameHashColumn) {
    // No ordering
    return migrationListAfterWithOffset(getTableName(), nameHashColumn, limit);
  }

  default List<String> listAfter(ListFilter filter, int limit, int offset) {
    return listAfter(getTableName(), getNameHashColumn(), filter.getCondition(), limit, offset);
  }

  default void exists(UUID id) {
    if (!exists(getTableName(), id.toString())) {
      String entityType = Entity.getEntityTypeFromClass(getEntityClass());
      throw EntityNotFoundException.byMessage(CatalogExceptionMessage.entityNotFound(entityType, id));
    }
  }

  default void existsByName(String fqn) {
    if (!existsByName(getTableName(), getNameHashColumn(), fqn)) {
      String entityType = Entity.getEntityTypeFromClass(getEntityClass());
      throw EntityNotFoundException.byMessage(CatalogExceptionMessage.entityNotFound(entityType, fqn));
    }
  }

  default int delete(String id) {
    int rowsDeleted = delete(getTableName(), id);
    if (rowsDeleted <= 0) {
      String entityType = Entity.getEntityTypeFromClass(getEntityClass());
      throw EntityNotFoundException.byMessage(entityNotFound(entityType, id));
    }
    return rowsDeleted;
  }
}
