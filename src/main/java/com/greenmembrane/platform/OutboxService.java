package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxService {
  private final BusinessRepository repo;
  private final TransactionTemplate tx;
  private final PlatformSettings cfg;
  private final Clock clock;
  private final IngestAdmission admission;

  @Autowired
  public OutboxService(BusinessRepository repo,TransactionTemplate tx,PlatformSettings cfg,IngestAdmission admission) {
    this(repo,tx,cfg,Clock.systemUTC(),admission);
  }
  public OutboxService(BusinessRepository repo, TransactionTemplate tx, PlatformSettings cfg) {
    this(repo, tx, cfg, Clock.systemUTC());
  }

  OutboxService(BusinessRepository repo, TransactionTemplate tx, PlatformSettings cfg, Clock clock) {
    this(repo,tx,cfg,clock,new IngestAdmission(repo,0,0));
  }
  private OutboxService(BusinessRepository repo, TransactionTemplate tx, PlatformSettings cfg, Clock clock, IngestAdmission admission) {
    this.admission=admission;
    this.repo = repo; this.tx = tx; this.cfg = cfg; this.clock = clock;
  }

  public boolean enabled() { return "rabbit".equals(cfg.execution()); }

  void ingestCreated(String taskId,String project) {
    created(taskId);
    if (enabled()) admission.added(project);
  }

  // Caller must be in the same transaction that creates the task.
  public void created(String taskId) {
    if (!enabled()) return;
    if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Task and outbox must share a transaction");
    repo.sql().update("UPDATE mb_tasks SET dispatch_mode='rabbit' WHERE id=?", taskId);
    repo.sql().update("INSERT INTO mb_task_outbox(task_id,generation,status,available_at) VALUES(?,0,'pending',?)",
        taskId, clock.millis());
  }

  public record Delivery(String taskId, int generation, String sendToken) {}

  public Delivery next(long confirmLeaseMillis) {
    return next(confirmLeaseMillis,0);
  }

  public Delivery next(long confirmLeaseMillis, int projectWindow) {
    return tx.execute(s -> {
      long now = clock.millis();
      List<Map<String,Object>> candidates;
      if (projectWindow>0) {
        // Separate statements under PostgreSQL READ COMMITTED: after reserving the gate,
        // the candidate query sees any window slot committed by the preceding publisher.
        if (repo.one("SELECT id FROM mb_dispatch_gate WHERE id=1 FOR UPDATE SKIP LOCKED")==null) return null;
        candidates=repo.sql().queryForList("""
            WITH active AS (
              SELECT t.project_id,COUNT(*) AS used FROM mb_task_outbox o
              JOIN mb_tasks t ON t.id=o.task_id WHERE o.status IN ('publishing','sent') GROUP BY t.project_id
            )
            SELECT o.task_id FROM mb_task_outbox o JOIN mb_tasks t ON t.id=o.task_id
            LEFT JOIN active a ON a.project_id=t.project_id
            WHERE (o.status='pending' AND o.available_at<=? AND COALESCE(a.used,0)<?)
               OR (o.status='publishing' AND o.send_lease<=?)
            ORDER BY o.available_at,o.task_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
            """,now,projectWindow,now);
      } else candidates=repo.sql().queryForList(
          "SELECT task_id FROM mb_task_outbox WHERE (status='pending' AND available_at<=?)"
          + " OR (status='publishing' AND send_lease<=?) ORDER BY available_at,task_id LIMIT 1 FOR UPDATE SKIP LOCKED",
          now, now);
      if (candidates.isEmpty()) return null;
      String id = text(candidates.get(0), "task_id"), token = id();
      int n = repo.sql().update(
          "UPDATE mb_task_outbox SET status='publishing',send_token=?,send_lease=?,send_attempts=send_attempts+1"
          + " WHERE task_id=? AND ((status='pending' AND available_at<=?) OR (status='publishing' AND send_lease<=?))",
          token, now + confirmLeaseMillis, id, now, now);
      if (n == 0) return null;
      var row = repo.required("SELECT generation FROM mb_task_outbox WHERE task_id=?", id);
      return new Delivery(id, ((Number) row.get("generation")).intValue(), token);
    });
  }

  public void published(Delivery d) {
    repo.sql().update("UPDATE mb_task_outbox SET status='sent',send_token=NULL,last_error=''"
        + " WHERE task_id=? AND generation=? AND status='publishing' AND send_token=?",
        d.taskId(), d.generation(), d.sendToken());
  }

  public void publishFailed(Delivery d, long delay) {
    repo.sql().update("UPDATE mb_task_outbox SET status='pending',send_token=NULL,available_at=?,last_error='broker_delivery_failed'"
        + " WHERE task_id=? AND generation=? AND status='publishing' AND send_token=?",
        clock.millis()+delay, d.taskId(), d.generation(), d.sendToken());
  }

  public record Claim(String taskId, int generation, String token, int attempt) {}

  public Claim claim(String taskId, int generation, long leaseMillis, int maxAttempts) {
    return claim(taskId,generation,leaseMillis,maxAttempts,600_000L);
  }

  public Claim claim(String taskId,int generation,long leaseMillis,int maxAttempts,long maxRunMillis) {
    if (leaseMillis<=0 || maxRunMillis<=0) throw new IllegalArgumentException("Task durations must be positive");
    return tx.execute(s -> {
      var task = repo.one("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE", taskId);
      if (task == null || !"rabbit".equals(text(task,"dispatch_mode"))
          || ((Number) task.get("generation")).intValue() != generation) return null;
      String state = text(task,"status");
      long now = clock.millis();
      if (!Set.of("queued","running").contains(state)) return null;
      if ("running".equals(state) && ((Number)task.get("deadline_at")).longValue()<=now) {
        terminal(task,"processing_deadline_exceeded");
        return null;
      }
      if ("running".equals(state) && ((Number)task.get("lease_until")).longValue() > now) return null;
      var event = repo.required("SELECT available_at FROM mb_task_outbox WHERE task_id=?", taskId);
      if (((Number)event.get("available_at")).longValue() > now) return null;
      int attempt = ((Number)task.get("attempt")).intValue()+1;
      if (attempt > maxAttempts) {
        terminal(task, "retry_exhausted");
        return null;
      }
      String token = id();
      long deadline=Math.addExact(now,maxRunMillis);
      repo.sql().update("UPDATE mb_tasks SET status='running',attempt=?,claim_token=?,execution_id=?,lease_until=?,deadline_at=?,last_error=''"
          + " WHERE id=?",
          attempt,token,"rabbit/"+generation,Math.min(now+leaseMillis,deadline),deadline,taskId);
      if ("ingest".equals(text(task,"task_type")))
        repo.sql().update("UPDATE mb_documents SET status='indexing' WHERE id=?", text(task,"target_id"));
      return new Claim(taskId,generation,token,attempt);
    });
  }

  public boolean renew(Claim c, long leaseMillis) {
    long now=clock.millis();
    return repo.sql().update("UPDATE mb_tasks SET lease_until=LEAST(?,deadline_at) WHERE id=? AND generation=?"
        + " AND claim_token=? AND status='running' AND lease_until>? AND deadline_at>?",
        now+leaseMillis,c.taskId(),c.generation(),c.token(),now,now)==1;
  }

  public void failed(Claim c, boolean retryable, int maxAttempts, long delay, String reason) {
    tx.executeWithoutResult(s -> {
      var task = repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE",c.taskId());
      if (!"running".equals(text(task,"status")) || !c.token().equals(text(task,"claim_token"))
          || ((Number)task.get("generation")).intValue()!=c.generation()) return;
      if (((Number)task.get("deadline_at")).longValue()<=clock.millis()) {
        terminal(task,"processing_deadline_exceeded"); return;
      }
      if (!retryable || c.attempt()>=maxAttempts) { terminal(task,reason); return; }
      repo.sql().update("UPDATE mb_tasks SET status='queued',claim_token=NULL,lease_until=0,last_error=? WHERE id=?",
          reason,c.taskId());
      pending(c.taskId(),c.generation(),delay);
    });
  }

  private void terminal(Map<String,Object> task, String reason) {
    String id = text(task,"id");
    repo.sql().update("UPDATE mb_tasks SET status='failed',claim_token=NULL,lease_until=0,last_error=? WHERE id=?",reason,id);
    if ("ingest".equals(text(task,"task_type")))
      repo.sql().update("UPDATE mb_documents SET status='failed' WHERE id=? AND status<>'indexed'",text(task,"target_id"));
    else repo.sql().update("UPDATE mb_reviews SET status='failed' WHERE id=? AND status='queued'",text(task,"target_id"));
    repo.sql().update("UPDATE mb_task_outbox SET status='done',send_token=NULL WHERE task_id=?",id);
  }

  private void pending(String taskId, int generation, long delay) {
    repo.sql().update("UPDATE mb_task_outbox SET status='pending',generation=?,available_at=?,send_token=NULL,send_lease=0"
        + " WHERE task_id=?",generation,clock.millis()+delay,taskId);
  }

  // Recovery is DB-driven: a duplicate may be acked while the original owner is still running.
  public void recover(int maxAttempts) {
    var expired=repo.sql().queryForList("SELECT id FROM mb_tasks WHERE dispatch_mode='rabbit' AND status='running'"
        + " AND (lease_until<=? OR deadline_at<=?) ORDER BY lease_until,id LIMIT 100",
        clock.millis(),clock.millis());
    for (var item:expired) tx.executeWithoutResult(s -> {
      var task=repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE",text(item,"id"));
      if (!"running".equals(text(task,"status"))) return;
      if (((Number)task.get("deadline_at")).longValue()<=clock.millis()) {
        terminal(task,"processing_deadline_exceeded"); return;
      }
      if (((Number)task.get("lease_until")).longValue()>clock.millis()) return;
      if (((Number)task.get("attempt")).intValue()>=maxAttempts) { terminal(task,"worker_lease_expired"); return; }
      repo.sql().update("UPDATE mb_tasks SET status='queued',claim_token=NULL,lease_until=0,last_error='worker_lease_expired' WHERE id=?",text(task,"id"));
      pending(text(task,"id"),((Number)task.get("generation")).intValue(),0);
    });
  }

  public Map<String,Object> retry(Identity who,String taskId,int expectedGeneration) {
    repo.role(who,"BUSINESS","DESIGNER","REVIEWER");
    return tx.execute(s -> {
      var task=repo.required("SELECT * FROM mb_tasks WHERE id=? FOR UPDATE",taskId);
      repo.project(who,text(task,"project_id"));
      int generation=((Number)task.get("generation")).intValue();
      if (!enabled() || !"rabbit".equals(text(task,"dispatch_mode")))
        throw error(HttpStatus.CONFLICT,"此重试接口仅用于 RabbitMQ 任务");
      // Repeating the same command is safe, but a newer failure requires a new expected generation.
      if (generation==expectedGeneration+1) return task;
      if (generation!=expectedGeneration || !"failed".equals(text(task,"status")))
        throw error(HttpStatus.CONFLICT,"任务状态或重试版本已变化");
      if ("ingest".equals(text(task,"task_type"))) admission.added(text(task,"project_id"));
      repo.sql().update("UPDATE mb_tasks SET status='queued',generation=generation+1,attempt=0,claim_token=NULL,"
          + "execution_id=NULL,lease_until=0,deadline_at=0,last_error='' WHERE id=?",taskId);
      if ("ingest".equals(text(task,"task_type")))
        repo.sql().update("UPDATE mb_documents SET status='uploaded' WHERE id=? AND status='failed'",text(task,"target_id"));
      if ("review".equals(text(task,"task_type")))
        repo.sql().update("UPDATE mb_reviews SET status='queued' WHERE id=? AND status='failed'",text(task,"target_id"));
      pending(taskId,generation+1,0);
      return repo.required("SELECT * FROM mb_tasks WHERE id=?",taskId);
    });
  }
}
