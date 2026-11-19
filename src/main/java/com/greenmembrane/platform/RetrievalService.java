package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
  private final BusinessRepository repo;
  private final ModelGateway model;
  private final PlatformSettings cfg;

  public RetrievalService(BusinessRepository repo, ModelGateway model, PlatformSettings cfg) {
    this.repo = repo;
    this.model = model;
    this.cfg = cfg;
  }

  public record Filters(String materialModel, String version, String documentType) {}

  public List<Map<String, Object>> search(
      Identity who, String project, String query, Filters filters, int limit, boolean publicOnly) {
    repo.project(who, project);
    if (query == null || query.isBlank() || query.length() > 4000)
      throw error(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "检索问题长度无效");
    limit = Math.max(1, Math.min(limit, 20));
    String normalized = model.normalize(query);
    var tokens = terms(normalized);
    StringBuilder where = new StringBuilder(" WHERE e.project_id=? AND d.status='indexed'");
    List<Object> params = new ArrayList<>();
    params.add(project);
    if (publicOnly && !model.allowsInternal()) where.append(" AND d.classification='public'");
    if (filters != null) {
      if (filters.materialModel() != null && !filters.materialModel().isBlank()) {
        where.append(" AND d.material_model=?");
        params.add(filters.materialModel());
      }
      if (filters.version() != null && !filters.version().isBlank()) {
        where.append(" AND d.business_version=?");
        params.add(filters.version());
      }
      if (filters.documentType() != null && !filters.documentType().isBlank()) {
        where.append(" AND d.doc_type=?");
        params.add(filters.documentType());
      }
    }
    String from = " FROM mb_evidence e JOIN mb_documents d ON d.id=e.document_id";
    String cols =
        "e.id AS evidence_id,e.document_id,e.body,e.location,e.text_sha256,d.name AS"
            + " filename,d.business_version,d.material_model,d.doc_type,d.classification";
    List<Object> lexicalArgs = new ArrayList<>();
    StringJoiner score = new StringJoiner("+");
    for (String token : tokens) {
      score.add("CASE WHEN LOWER(e.body) LIKE ? ESCAPE '!' THEN 1 ELSE 0 END");
      lexicalArgs.add("%" + token.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
    }
    if (tokens.isEmpty()) return List.of();
    String ranking = score.toString();
    lexicalArgs.addAll(params);
    var lexical =
        repo.sql()
            .queryForList(
                "SELECT * FROM (SELECT "
                    + cols
                    + ",("
                    + ranking
                    + ") AS lexical_score"
                    + from
                    + where
                    + ") ranked WHERE lexical_score>0 ORDER BY lexical_score DESC,evidence_id LIMIT"
                    + " 40",
                lexicalArgs.toArray());
    if (!cfg.vectorEnabled())
      return model.rerankEnabled()
          ? model.rerank(query, lexical, limit)
          : lexical.stream().limit(limit).toList();
    float[] vector = model.embed(normalized);
    List<Object> vectorArgs = new ArrayList<>(params);
    vectorArgs.add(namespace());
    vectorArgs.add(vector(vector));
    var dense =
        repo.sql()
            .queryForList(
                "SELECT "
                    + cols
                    + from
                    + " JOIN mb_vectors v ON v.evidence_id=e.id"
                    + where
                    + " AND v.namespace=? ORDER BY v.embedding <=> CAST(? AS vector) LIMIT 40",
                vectorArgs.toArray());
    var fused = fuse(lexical, dense, model.rerankEnabled() ? 40 : limit);
    return model.rerankEnabled() ? model.rerank(query, fused, limit) : fused;
  }

  static List<String> terms(String input) {
    Set<String> terms = new LinkedHashSet<>();
    var ascii =
        java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]*")
            .matcher(input.toLowerCase(Locale.ROOT));
    while (ascii.find() && terms.size() < 12) terms.add(ascii.group());
    var chinese = java.util.regex.Pattern.compile("[\\p{IsHan}]+").matcher(input);
    while (chinese.find() && terms.size() < 24) {
      String value = chinese.group();
      if (value.length() <= 2) terms.add(value);
      else
        for (int i = 0; i < value.length() - 1 && terms.size() < 24; i++)
          terms.add(value.substring(i, i + 2));
    }
    return new ArrayList<>(terms);
  }

  public String namespace() {
    return sha(cfg.embeddingModel() + ":" + cfg.embeddingDimensions());
  }

  static String vector(float[] vector) {
    return Arrays.toString(vector);
  }

  public static List<Map<String, Object>> fuse(
      List<Map<String, Object>> lexical, List<Map<String, Object>> dense, int limit) {
    Map<String, Double> scores = new HashMap<>();
    Map<String, Map<String, Object>> rows = new HashMap<>();
    for (var list : List.of(lexical, dense))
      for (int i = 0; i < list.size(); i++) {
        var row = list.get(i);
        String id = text(row, "evidence_id");
        rows.putIfAbsent(id, row);
        scores.merge(id, 1.0 / (60 + i + 1), Double::sum);
      }
    return scores.entrySet().stream()
        .sorted(
            Map.Entry.<String, Double>comparingByValue()
                .reversed()
                .thenComparing(Map.Entry.comparingByKey()))
        .limit(limit)
        .map(
            entry -> {
              var row = new LinkedHashMap<>(rows.get(entry.getKey()));
              row.put("rrf_score", entry.getValue());
              return (Map<String, Object>) row;
            })
        .toList();
  }

  public List<Map<String, Object>> selectedEvidence(
      Identity who, String project, List<String> ids) {
    repo.project(who, project);
    if (ids.isEmpty() || ids.size() > 20)
      throw error(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "请选择1至20份资料");
    List<Map<String, Object>> result = new ArrayList<>();
    for (String id : new LinkedHashSet<>(ids)) {
      var doc = repo.document(who, id);
      if (!project.equals(text(doc, "project_id")) || !"indexed".equals(text(doc, "status")))
        throw error(org.springframework.http.HttpStatus.CONFLICT, "资料不属于该项目或尚未索引");
      result.addAll(
          repo.sql()
              .queryForList(
                  "SELECT e.id AS evidence_id,e.document_id,e.body,e.location,e.text_sha256,d.name"
                      + " AS filename,d.business_version,d.material_model,d.doc_type,d.classification,d.sha256"
                      + " FROM mb_evidence e JOIN mb_documents d ON d.id=e.document_id WHERE d.id=?"
                      + " ORDER BY e.chunk_index",
                  id));
    }
    if (result.size() > 120)
      throw error(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "审查来源超过120片段，请缩小资料范围");
    return result;
  }
}
