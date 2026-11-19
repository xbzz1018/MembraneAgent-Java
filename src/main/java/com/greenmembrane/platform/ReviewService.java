package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.util.*;
import java.util.regex.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewService {
  private final BusinessRepository repo;
  private final RetrievalService retrieval;
  private final TransactionTemplate tx;
  private final DocumentService documents;
  private final ModelGateway model;
  private final OutboxService outbox;

  public ReviewService(
      BusinessRepository repo,
      RetrievalService retrieval,
      TransactionTemplate tx,
      DocumentService documents,
      ModelGateway model, OutboxService outbox) {
    this.repo = repo;
    this.retrieval = retrieval;
    this.tx = tx;
    this.documents = documents;
    this.model = model;
    this.outbox = outbox;
  }

  public Map<String, Object> create(
      Identity who, String project, List<String> documentIds, String requestKey) {
    repo.project(who, project);
    repo.role(who, "BUSINESS", "DESIGNER", "REVIEWER");
    if (documentIds == null || documentIds.stream().anyMatch(Objects::isNull))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "资料ID无效");
    List<String> ids = documentIds.stream().distinct().sorted().toList();
    String requestHash = sha(json(ids));
    var existing =
        repo.one(
            "SELECT * FROM mb_tasks WHERE project_id=? AND task_type='review' AND request_key=?",
            project,
            requestKey);
    if (existing != null) return sameRequest(existing, requestHash);
    var snapshot = retrieval.selectedEvidence(who, project, ids);
    if (snapshot.isEmpty()) throw error(HttpStatus.CONFLICT, "没有可冻结的证据");
    if (snapshot.stream().anyMatch(e -> !"public".equals(text(e, "classification")))
        && !model.allowsInternal()) throw error(HttpStatus.CONFLICT, "当前模型提供方不能处理非公开资料，请配置本地提供方");
    String encoded = json(snapshot), reviewId = id(), taskId = id();
    try {
      return tx.execute(
          status -> {
            repo.sql()
                .update(
                    "INSERT INTO mb_reviews VALUES(?,?,?,?,?,?,?,?,?)",
                    reviewId,
                    project,
                    json(ids),
                    "queued",
                    0,
                    reviewHash(encoded, ""),
                    "",
                    encoded,
                    who.userId());
            repo.sql()
                .update(
                    "INSERT INTO"
                        + " mb_tasks(id,project_id,task_type,target_id,request_key,request_hash,status,created_at)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    taskId,
                    project,
                    "review",
                    reviewId,
                    requestKey,
                    requestHash,
                    "queued",
                    now());
            outbox.created(taskId);
            repo.event(project, who.userId(), "review.requested", reviewId, sha(encoded));
            return repo.required("SELECT * FROM mb_tasks WHERE id=?", taskId);
          });
    } catch (DuplicateKeyException ex) {
      return sameRequest(
          repo.required(
              "SELECT * FROM mb_tasks WHERE project_id=? AND task_type='review' AND request_key=?",
              project,
              requestKey),
          requestHash);
    }
  }

  static Map<String, Object> sameRequest(Map<String, Object> task, String hash) {
    if (!hash.equals(text(task, "request_hash"))) throw error(HttpStatus.CONFLICT, "同一幂等键的请求内容不同");
    return task;
  }

  public record Finding(
      String id, String summary, List<String> evidenceIds, String actual, String expected) {}

  public List<Finding> rules(List<Map<String, Object>> evidence) {
    List<Finding> out = new ArrayList<>();
    Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (var e : evidence)
      groups.computeIfAbsent(text(e, "document_id"), k -> new ArrayList<>()).add(e);
    var designs =
        groups.values().stream().filter(g -> "design".equals(text(g.get(0), "doc_type"))).toList();
    if (designs.isEmpty()) {
      out.add(
          new Finding(
              id(),
              "缺少设计资料，需要补充后复核",
              evidence.isEmpty() ? List.of() : List.of(text(evidence.get(0), "evidence_id")),
              "未提供",
              "设计资料"));
      return out;
    }
    for (var design : designs) {
      var first = design.get(0);
      String model = text(first, "material_model");
      var specs =
          groups.values().stream()
              .filter(
                  g ->
                      "spec".equals(text(g.get(0), "doc_type"))
                          && model.equals(text(g.get(0), "material_model")))
              .toList();
      if (model.isBlank() || specs.size() != 1) {
        out.add(
            new Finding(
                id(),
                model.isBlank()
                    ? "设计资料缺少明确型号，需人工确认"
                    : specs.isEmpty() ? "缺少同型号规格依据，需补充" : "同型号存在多份规格依据，需确认适用版本",
                List.of(text(first, "evidence_id")),
                "依据不足或不唯一",
                "明确的适用规格依据"));
        continue;
      }
      compare(out, design, specs.get(0), "厚度", "mm", false);
      compare(out, design, specs.get(0), "抗拉强度", "N/5cm", true);
    }
    return out;
  }

  private record Measurement(double value, String evidenceId) {}

  private List<Measurement> measurements(
      List<Map<String, Object>> evidence, String label, String unit) {
    Map<Double, Measurement> found = new LinkedHashMap<>();
    var pattern =
        Pattern.compile(
            Pattern.quote(label)
                + "\\s*(?:不低于|至少|为|:|：|≥|>=)?\\s*(\\d+(?:\\.\\d+)?)\\s*"
                + Pattern.quote(unit),
            Pattern.CASE_INSENSITIVE);
    for (var e : evidence) {
      var matcher = pattern.matcher(text(e, "body"));
      while (matcher.find()) {
        double value = Double.parseDouble(matcher.group(1));
        found.putIfAbsent(value, new Measurement(value, text(e, "evidence_id")));
      }
    }
    return new ArrayList<>(found.values());
  }

  private void compare(
      List<Finding> out,
      List<Map<String, Object>> design,
      List<Map<String, Object>> spec,
      String label,
      String unit,
      boolean minimum) {
    var a = measurements(design, label, unit);
    var b = measurements(spec, label, unit);
    List<String> refs =
        List.of(
            a.isEmpty() ? text(design.get(0), "evidence_id") : a.get(0).evidenceId(),
            b.isEmpty() ? text(spec.get(0), "evidence_id") : b.get(0).evidenceId());
    if (a.size() != 1 || b.size() != 1) {
      out.add(
          new Finding(
              id(),
              label + "缺失或存在多个值，需确认适用范围及单位",
              refs,
              a.isEmpty() ? "未识别" : a.toString(),
              b.isEmpty() ? "未识别" : b.toString()));
      return;
    }
    double actual = a.get(0).value(), expected = b.get(0).value();
    if (minimum ? actual >= expected : Double.compare(actual, expected) == 0) return;
    out.add(
        new Finding(
            id(),
            label + "与选定的同型号规格依据不一致，需人工核对",
            refs,
            actual + " " + unit,
            expected + " " + unit));
  }

  public Map<String, Object> view(Identity who, String id) {
    var review = new LinkedHashMap<>(repo.review(who, id));
    review.remove("snapshot");
    review.put(
        "issues",
        repo.sql().queryForList("SELECT * FROM mb_issues WHERE review_id=? ORDER BY id", id));
    review.put(
        "sources",
        list(text(repo.review(who, id), "snapshot")).stream()
            .map(
                e ->
                    Map.of(
                        "evidence_id",
                        text(e, "evidence_id"),
                        "filename",
                        text(e, "filename"),
                        "location",
                        text(e, "location"),
                        "version",
                        text(e, "business_version")))
            .toList());
    return review;
  }

  public Map<String, Object> resolve(
      Identity who, String id, String issueId, int expected, String reason) {
    repo.role(who, "REVIEWER");
    repo.review(who, id);
    return tx.execute(
        status -> {
          var review = repo.required("SELECT * FROM mb_reviews WHERE id=? FOR UPDATE", id);
          if (!"awaiting_approval".equals(text(review, "status"))
              || ((Number) review.get("lock_version")).intValue() != expected)
            throw error(HttpStatus.CONFLICT, "审查状态或版本已变化");
          int updated =
              repo.sql()
                  .update(
                      "UPDATE mb_issues SET status='resolved' WHERE id=? AND review_id=? AND"
                          + " status='pending_human'",
                      issueId,
                      id);
          if (updated != 1) throw error(HttpStatus.CONFLICT, "问题不存在或已处理");
          repo.sql().update("UPDATE mb_reviews SET lock_version=lock_version+1 WHERE id=?", id);
          repo.event(text(review, "project_id"), who.userId(), "issue.resolved", issueId, reason);
          return view(who, id);
        });
  }

  public Map<String, Object> decide(
      Identity who,
      String id,
      String commandId,
      int expected,
      String contentHash,
      String decision,
      String reason) {
    repo.role(who, "REVIEWER");
    var authorized = repo.review(who, id);
    if (!Set.of("approved", "rejected").contains(decision))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "审批决定无效");
    String requestHash = sha(json(List.of(id, expected, contentHash, decision, reason)));
    return tx.execute(
        status -> {
          // Lock the task before review, matching cancellation and publication lock order.
          var task =
              repo.required(
                  "SELECT * FROM mb_tasks WHERE task_type='review' AND target_id=? FOR UPDATE", id);
          var prior = repo.one("SELECT * FROM mb_commands WHERE id=?", commandId);
          if (prior != null) {
            if (!id.equals(text(prior, "review_id"))
                || !who.userId().equals(text(prior, "actor"))
                || !requestHash.equals(text(prior, "request_hash")))
              throw error(HttpStatus.CONFLICT, "审批幂等键已被其他请求使用");
            return read(text(prior, "receipt"), Map.class);
          }
          var review = repo.required("SELECT * FROM mb_reviews WHERE id=? FOR UPDATE", id);
          if (!"awaiting_approval".equals(text(review, "status"))
              || ((Number) review.get("lock_version")).intValue() != expected
              || !contentHash.equals(text(review, "content_hash")))
            throw error(HttpStatus.CONFLICT, "版本、证据快照或审批状态已变化");
          if ("approved".equals(decision)) {
            Integer pending =
                repo.sql()
                    .queryForObject(
                        "SELECT COUNT(*) FROM mb_issues WHERE review_id=? AND"
                            + " status='pending_human'",
                        Integer.class,
                        id);
            if (pending != 0) throw error(HttpStatus.CONFLICT, "仍有待复核问题，不能批准");
            verifySnapshot(review);
          }
          repo.sql()
              .update(
                  "UPDATE mb_reviews SET status=?,lock_version=lock_version+1 WHERE id=?",
                  decision,
                  id);
          repo.sql().update("UPDATE mb_tasks SET status=? WHERE id=?", decision, text(task, "id"));
          Map<String, Object> receipt =
              Map.of(
                  "review_id",
                  id,
                  "status",
                  decision,
                  "lock_version",
                  expected + 1,
                  "command_id",
                  commandId);
          repo.sql()
              .update(
                  "INSERT INTO mb_commands VALUES(?,?,?,?,?,?,?)",
                  commandId,
                  id,
                  who.userId(),
                  requestHash,
                  decision,
                  reason,
                  json(receipt));
          repo.event(
              text(authorized, "project_id"), who.userId(), "review." + decision, id, reason);
          return receipt;
        });
  }

  static String reviewHash(String snapshot, String report) {
    return sha(json(List.of(snapshot, report)));
  }

  public void verifySnapshot(Map<String, Object> review) {
    String snapshot = text(review, "snapshot");
    if (!reviewHash(snapshot, text(review, "report")).equals(text(review, "content_hash")))
      throw error(HttpStatus.CONFLICT, "冻结快照损坏");
    for (var e : list(snapshot)) {
      var current = repo.required("SELECT * FROM mb_documents WHERE id=?", text(e, "document_id"));
      if (!"indexed".equals(text(current, "status"))
          || (!"public".equals(text(current, "classification")) && !model.allowsInternal())
          || !text(current, "sha256").equals(text(e, "sha256")))
        throw error(HttpStatus.CONFLICT, "来源已变化");
      documents.bytes(current);
      var chunk =
          repo.required(
              "SELECT body,text_sha256 FROM mb_evidence WHERE id=?", text(e, "evidence_id"));
      if (!text(chunk, "text_sha256").equals(text(e, "text_sha256"))
          || !sha(text(chunk, "body")).equals(text(e, "text_sha256"))
          || !sha(text(e, "body")).equals(text(e, "text_sha256")))
        throw error(HttpStatus.CONFLICT, "来源片段已变化");
    }
  }
}
