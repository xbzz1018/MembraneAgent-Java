package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskService {
  private final BusinessRepository repo;
  private final TransactionTemplate tx;
  private final OutboxService outbox;

  public TaskService(BusinessRepository repo, TransactionTemplate tx, OutboxService outbox) {
    this.repo = repo;
    this.tx = tx;
    this.outbox = outbox;
  }

  public Map<String, Object> index(Identity who, String documentId) {
    repo.role(who, "BUSINESS", "DESIGNER");
    var doc = repo.document(who, documentId);
    String taskId = "ingest-" + documentId;
    var existing = repo.one("SELECT * FROM mb_tasks WHERE id=?", taskId);
    if (existing != null) return existing;
    try {
      return tx.execute(status -> {
        repo.required("SELECT id FROM mb_documents WHERE id=? FOR UPDATE",documentId);
        var prior=repo.one("SELECT * FROM mb_tasks WHERE id=?",taskId);
        if (prior!=null) return prior;
        repo.sql().update(
            "INSERT INTO mb_tasks(id,project_id,task_type,target_id,request_key,request_hash,status,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",taskId,text(doc,"project_id"),"ingest",documentId,documentId,text(doc,"sha256"),"queued",now());
        outbox.ingestCreated(taskId,text(doc,"project_id"));
        return repo.required("SELECT * FROM mb_tasks WHERE id=?",taskId);
      });
    } catch (DuplicateKeyException ignored) {
      return repo.required("SELECT * FROM mb_tasks WHERE id=?",taskId);
    }
  }

  public Map<String, Object> get(Identity who, String id) {
    var row = repo.required("SELECT * FROM mb_tasks WHERE id=?", id);
    repo.project(who, text(row, "project_id"));
    var safe = new LinkedHashMap<>(row);
    safe.remove("claim_token");
    safe.remove("execution_id");
    safe.remove("lease_until");
    safe.remove("deadline_at");
    var delivery=repo.one("SELECT status,last_error,send_attempts FROM mb_task_outbox WHERE task_id=?",id);
    if (delivery!=null) safe.put("delivery",delivery);
    return safe;
  }

  public void cancel(Identity who, String id) {
    var visible = get(who, id);
    repo.role(who, "BUSINESS", "DESIGNER", "REVIEWER");
    tx.executeWithoutResult(
        status -> {
          var task = repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE", id);
          if ("cancelled".equals(text(task, "status"))) return;
          if (!Set.of("queued", "running", "awaiting_approval").contains(text(task, "status")))
            throw error(HttpStatus.CONFLICT, "任务已终止");
          repo.sql()
              .update("UPDATE mb_tasks SET status='cancelled',claim_token=NULL,lease_until=0 WHERE id=?", id);
          repo.sql().update("UPDATE mb_task_outbox SET status='done',send_token=NULL WHERE task_id=?", id);
          if ("review".equals(text(task, "task_type")))
            repo.sql()
                .update(
                    "UPDATE mb_reviews SET status='cancelled',lock_version=lock_version+1 WHERE"
                        + " id=?",
                    text(task, "target_id"));
          else
            repo.sql()
                .update(
                    "UPDATE mb_documents SET status='cancelled' WHERE id=? AND status<>'indexed'",
                    text(task, "target_id"));
          repo.event(text(visible, "project_id"), who.userId(), "task.cancelled", id, "");
        });
  }

  public String claim(String id, String execution, int attempt) {
    return tx.execute(
        status -> {
          var row = repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE", id);
          String state = text(row, "status");
          if (!Set.of("queued", "running").contains(state)) return null;
          String old = text(row, "execution_id");
          if (!old.isEmpty()
              && (!old.equals(execution) || ((Number) row.get("attempt")).intValue() >= attempt))
            throw error(HttpStatus.CONFLICT, "旧执行不能接管任务");
          String claim = id();
          repo.sql()
              .update(
                  "UPDATE mb_tasks SET status='running',execution_id=?,attempt=?,claim_token=?"
                      + " WHERE id=?",
                  execution,
                  attempt,
                  claim,
                  id);
          if ("ingest".equals(text(row, "task_type")))
            repo.sql()
                .update(
                    "UPDATE mb_documents SET status='indexing' WHERE id=?", text(row, "target_id"));
          return claim;
        });
  }

  public void checkClaim(String id, String claim) {
    validateClaim(repo.required("SELECT status,claim_token,dispatch_mode,lease_until,deadline_at FROM mb_tasks WHERE id=?",id),claim);
  }

  public void assertClaim(String id, String claim) {
    validateClaim(repo.required("SELECT status,claim_token,dispatch_mode,lease_until,deadline_at FROM mb_tasks WHERE id=? FOR UPDATE",id),claim);
  }

  private void validateClaim(Map<String,Object> row,String claim) {
    if (!"running".equals(text(row, "status")) || !claim.equals(text(row, "claim_token"))
        || ("rabbit".equals(text(row,"dispatch_mode"))
            && (((Number)row.get("lease_until")).longValue()<=System.currentTimeMillis()
                || ((Number)row.get("deadline_at")).longValue()<=System.currentTimeMillis())))
      throw error(HttpStatus.CONFLICT, "执行认领已失效，禁止迟到写入");
  }

  public void failClaim(String id, String claim) {
    tx.executeWithoutResult(
        status -> {
          var row = repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE", id);
          if ("running".equals(text(row, "status")) && claim.equals(text(row, "claim_token")))
            fail(id);
        });
  }

  public void fail(String id) {
    tx.executeWithoutResult(
        status -> {
          var task = repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE", id);
          if (!Set.of("running", "queued").contains(text(task, "status"))) return;
          repo.sql().update("UPDATE mb_tasks SET status='failed',claim_token=NULL WHERE id=?", id);
          if ("review".equals(text(task, "task_type")))
            repo.sql()
                .update(
                    "UPDATE mb_reviews SET status='failed' WHERE id=?", text(task, "target_id"));
          else
            repo.sql()
                .update(
                    "UPDATE mb_documents SET status='failed' WHERE id=?", text(task, "target_id"));
        });
  }
}
