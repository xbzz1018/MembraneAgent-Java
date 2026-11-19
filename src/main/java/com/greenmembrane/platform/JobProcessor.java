package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobProcessor {
  private final BusinessRepository repo;
  private final TaskService tasks;
  private final DocumentService documents;
  private final DocumentParser parser;
  private final ReviewService reviews;
  private final ModelGateway model;
  private final PlatformSettings cfg;
  private final TransactionTemplate tx;
  private final RetrievalService retrieval;
  private final SpecificationCache specificationCache;

  public JobProcessor(
      BusinessRepository repo,
      TaskService tasks,
      DocumentService documents,
      DocumentParser parser,
      ReviewService reviews,
      ModelGateway model,
      PlatformSettings cfg,
      TransactionTemplate tx,
      RetrievalService retrieval, SpecificationCache specificationCache) {
    this.repo = repo;
    this.tasks = tasks;
    this.documents = documents;
    this.parser = parser;
    this.reviews = reviews;
    this.model = model;
    this.cfg = cfg;
    this.tx = tx;
    this.retrieval = retrieval;
    this.specificationCache = specificationCache;
  }

  public void process(String taskId, String execution, int attempt) {
    String claim = tasks.claim(taskId, execution, attempt);
    if (claim == null) return;
    processClaimed(taskId, claim);
  }

  public void processClaimed(String taskId, String claim) {
    var task = repo.required("SELECT * FROM mb_tasks WHERE id=?", taskId);
    try {
      tasks.checkClaim(taskId,claim);
      if ("ingest".equals(text(task, "task_type"))) ingest(task, claim);
      else review(task, claim);
    } catch (RuntimeException ex) {
      if ("inline".equals(cfg.execution())) tasks.failClaim(taskId, claim);
      throw ex;
    }
  }

  private void ingest(Map<String, Object> task, String claim) {
    String documentId = text(task, "target_id");
    var doc = repo.required("SELECT * FROM mb_documents WHERE id=?", documentId);
    if (!text(doc,"sha256").equals(text(task,"request_hash")))
      throw error(org.springframework.http.HttpStatus.CONFLICT,"任务绑定的资料已变化");
    var chunks = parser.parse(text(doc, "name"), documents.bytes(doc));
    String full = String.join("\n", chunks.stream().map(DocumentParser.Chunk::text).toList());
    if (!text(doc, "material_model").isBlank() && !full.contains(text(doc, "material_model")))
      throw error(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "声明型号在原文中不存在");
    List<float[]> vectors = new ArrayList<>();
    if (cfg.vectorEnabled()) {
      if (!"public".equals(text(doc, "classification")) && !model.allowsInternal())
        throw error(org.springframework.http.HttpStatus.CONFLICT, "未配置非公开资料Embedding处理");
      for (var chunk : chunks) {
        tasks.checkClaim(text(task,"id"),claim);
        vectors.add(model.embed(chunk.text()));
      }
    }
    tx.executeWithoutResult(
        status -> {
          tasks.assertClaim(text(task, "id"), claim);
          repo.sql().update("DELETE FROM mb_evidence WHERE document_id=?", documentId);
          for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            String eid = documentId + ":" + i;
            repo.sql()
                .update(
                    "INSERT INTO mb_evidence VALUES(?,?,?,?,?,?,?)",
                    eid,
                    documentId,
                    text(task, "project_id"),
                    i,
                    chunk.text(),
                    chunk.location(),
                    sha(chunk.text()));
            if (cfg.vectorEnabled())
              repo.sql()
                  .update(
                      "INSERT INTO mb_vectors(evidence_id,namespace,embedding) VALUES(?,?,CAST(? AS"
                          + " vector))",
                      eid,
                      retrieval.namespace(),
                      RetrievalService.vector(vectors.get(i)));
          }
          repo.sql().update("UPDATE mb_documents SET status='indexed' WHERE id=?", documentId);
          if ("spec".equals(text(doc,"doc_type")))
            specificationCache.invalidateAfterCommit(text(doc,"project_id"),text(doc,"material_model"),text(doc,"business_version"));
          tasks.assertClaim(text(task,"id"),claim);
          int completed=repo.sql().update("UPDATE mb_tasks SET status='completed',claim_token=NULL,lease_until=0"
              + " WHERE id=? AND generation=? AND status='running' AND claim_token=?",
              text(task,"id"),task.get("generation"),claim);
          if (completed!=1) throw error(org.springframework.http.HttpStatus.CONFLICT,"任务执行版本已变化");
          repo.sql().update("UPDATE mb_task_outbox SET status='done',send_token=NULL WHERE task_id=?",text(task,"id"));
        });
  }

  private void review(Map<String, Object> task, String claim) {
    String reviewId = text(task, "target_id");
    var review = repo.required("SELECT * FROM mb_reviews WHERE id=?", reviewId);
    var evidence = list(text(review, "snapshot"));
    reviews.verifySnapshot(review);
    var findings = reviews.rules(evidence);
    StringBuilder report = new StringBuilder("# 工程资料核对报告\n自动核对仅覆盖已实现规则，需人工复核，不签发工程合格结论。\n");
    for (var item : findings)
      report
          .append("\n- ")
          .append(item.summary())
          .append("；实际：")
          .append(item.actual())
          .append("；依据：")
          .append(item.expected())
          .append(" ")
          .append(item.evidenceIds())
          .append("\n");
    if (findings.isEmpty()) report.append("\n未发现当前规则覆盖范围内的可证实冲突，仍需审核人核对。\n");
    report.append("\n## 原始依据\n");
    for (var e : evidence)
      report
          .append("- [")
          .append(text(e, "evidence_id"))
          .append("] ")
          .append(text(e, "filename"))
          .append(" ")
          .append(text(e, "location"))
          .append("\n");
    tasks.checkClaim(text(task,"id"),claim);
    String extra =
        findings.isEmpty() ? "" : model.organize(json(findings), text(review, "snapshot"));
    if (!extra.isBlank()) {
      Set<String> known = new HashSet<>();
      for (var e : evidence) known.add(text(e, "evidence_id"));
      var matcher = java.util.regex.Pattern.compile("\\[([^\\[\\]]+)\\]").matcher(extra);
      while (matcher.find())
        if (!known.contains(matcher.group(1)))
          throw error(org.springframework.http.HttpStatus.BAD_GATEWAY, "整理结果引用未知证据");
      if (!ModelGateway.identifiers(json(findings) + text(review, "snapshot"))
          .containsAll(ModelGateway.identifiers(extra)))
        throw error(org.springframework.http.HttpStatus.BAD_GATEWAY, "整理结果出现未支持数字或型号");
      report.append("\n## 辅助说明（以结构化问题单为准，需人工复核）\n").append(extra);
    }
    String finalReport = report.toString();
    reviews.verifySnapshot(review);
    tx.executeWithoutResult(
        status -> {
          tasks.assertClaim(text(task, "id"), claim);
          for (var item : findings)
            repo.sql()
                .update(
                    "INSERT INTO mb_issues VALUES(?,?,?,?,?,?,?)",
                    item.id(),
                    reviewId,
                    item.summary(),
                    json(item.evidenceIds()),
                    "pending_human",
                    item.actual(),
                    item.expected());
          repo.sql()
              .update(
                  "UPDATE mb_reviews SET"
                      + " report=?,content_hash=?,status='awaiting_approval',lock_version=1 WHERE"
                      + " id=? AND status='queued'",
                  finalReport,
                  ReviewService.reviewHash(text(review, "snapshot"), finalReport),
                  reviewId);
          tasks.assertClaim(text(task,"id"),claim);
          int completed=repo.sql()
              .update(
                  "UPDATE mb_tasks SET status='awaiting_approval',claim_token=NULL,lease_until=0"
                      + " WHERE id=? AND generation=? AND status='running' AND claim_token=?",
                  text(task,"id"),task.get("generation"),claim);
          if (completed!=1) throw error(org.springframework.http.HttpStatus.CONFLICT,"任务执行版本已变化");
          repo.sql().update("UPDATE mb_task_outbox SET status='done',send_token=NULL WHERE task_id=?",text(task,"id"));
        });
  }
}
