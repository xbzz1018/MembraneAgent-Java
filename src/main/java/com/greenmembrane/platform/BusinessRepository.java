package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.util.*;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Repository;

@Repository
public class BusinessRepository {
  private final SqlAccess db;

  public SqlAccess sql() {
    return db;
  }

  public BusinessRepository(SqlAccess db) {
    this.db = db;
  }

  public Map<String, Object> one(String sql, Object... args) {
    List<Map<String, Object>> rows = db.queryForList(sql, args);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public Map<String, Object> required(String sql, Object... args) {
    Map<String, Object> row = one(sql, args);
    if (row == null) throw error(HttpStatus.NOT_FOUND, "对象不存在或无访问权限");
    return row;
  }

  public void project(Identity who, String project) {
    required("SELECT id FROM mb_projects WHERE id=? AND tenant_id=?", project, who.tenantId());
    if (!who.admin())
      required(
          "SELECT project_id FROM mb_members WHERE project_id=? AND user_id=?",
          project,
          who.userId());
  }

  public void role(Identity who, String... allowed) {
    if (!who.admin() && !Arrays.asList(allowed).contains(who.role()))
      throw error(HttpStatus.FORBIDDEN, "当前角色不能执行此操作");
  }

  public Map<String, Object> document(Identity who, String id) {
    Map<String, Object> row = required("SELECT * FROM mb_documents WHERE id=?", id);
    project(who, text(row, "project_id"));
    return row;
  }

  public Map<String, Object> review(Identity who, String id) {
    Map<String, Object> row = required("SELECT * FROM mb_reviews WHERE id=?", id);
    project(who, text(row, "project_id"));
    return row;
  }

  public void event(String project, String actor, String kind, String target, String detail) {
    db.update(
        "INSERT INTO mb_events VALUES(?,?,?,?,?,?,?)",
        id(),
        project,
        actor,
        kind,
        target,
        detail,
        now());
  }
}
