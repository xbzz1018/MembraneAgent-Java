package com.greenmembrane.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Admission applies to new ingest work, including an explicit retry of a failed task. */
@Component
public final class IngestAdmission {
  private final BusinessRepository repo;
  private final int projectLimit, totalLimit;

  public IngestAdmission(BusinessRepository repo,
      @Value("${app.jobs.project-backlog-limit:0}") int projectLimit,
      @Value("${app.jobs.total-backlog-limit:0}") int totalLimit) {
    if (projectLimit<0 || totalLimit<0 || Math.max(projectLimit,totalLimit)>1000000)
      throw new IllegalArgumentException("Invalid ingest backlog limits");
    this.repo=repo; this.projectLimit=projectLimit; this.totalLimit=totalLimit;
  }

  void added(String project) {
    if (projectLimit==0 && totalLimit==0) return;
    for (var sync:TransactionSynchronizationManager.getSynchronizations()) {
      if (sync instanceof Check check && check.project.equals(project)) { check.added++; return; }
    }
    TransactionSynchronizationManager.registerSynchronization(new Check(project));
  }

  private final class Check implements TransactionSynchronization {
    final String project;
    int added=1;
    Check(String project) { this.project=project; }

    @Override public void beforeCommit(boolean readOnly) {
      if ((projectLimit>0 && added>projectLimit) || (totalLimit>0 && added>totalLimit))
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"单个批次的新增文件数超过入库容量，请拆分后提交");
      // Last DB checks before commit. At READ COMMITTED, the next holder sees the preceding
      // admission; this transaction's own tasks are already in the count. Workers never take this lock.
      repo.required("SELECT id FROM mb_ingest_gate WHERE id=1 FOR UPDATE");
      String active="SELECT 1 FROM mb_tasks WHERE dispatch_mode='rabbit' AND task_type='ingest' AND status IN ('queued','running')";
      if (projectLimit>0 && repo.sql().queryForObject("SELECT COUNT(*) FROM ("+active+" AND project_id=? LIMIT ?) q",
          Long.class,project,projectLimit+1)>projectLimit) throw new Full(true);
      if (totalLimit>0 && repo.sql().queryForObject("SELECT COUNT(*) FROM ("+active+" LIMIT ?) q",Long.class,totalLimit+1)>totalLimit)
        throw new Full(false);
    }
  }

  static final class Full extends ResponseStatusException {
    final String code;
    Full(boolean project) {
      super(project?HttpStatus.TOO_MANY_REQUESTS:HttpStatus.SERVICE_UNAVAILABLE,
          project?"本项目待处理文件已满，请稍后重试":"平台待处理文件已满，请稍后重试");
      code=project?"project_ingest_backlog_full":"platform_ingest_backlog_full";
    }
  }
}
