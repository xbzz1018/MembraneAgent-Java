package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
  private final BusinessRepository repo;
  private final DocumentService documents;
  private final TaskService tasks;
  private final TaskDispatcher dispatch;
  private final RetrievalService retrieval;
  private final ReviewService reviews;
  private final AgentService agents;
  private final ReportService reports;
  private final PlatformSettings cfg;
  private final CatalogService catalog;

  public ApiController(
      BusinessRepository repo,
      DocumentService documents,
      TaskService tasks,
      TaskDispatcher dispatch,
      RetrievalService retrieval,
      ReviewService reviews,
      AgentService agents,
      ReportService reports,
      PlatformSettings cfg, CatalogService catalog) {
    this.repo = repo;
    this.documents = documents;
    this.tasks = tasks;
    this.dispatch = dispatch;
    this.retrieval = retrieval;
    this.reviews = reviews;
    this.agents = agents;
    this.reports = reports;
    this.cfg = cfg;
    this.catalog=catalog;
  }

  public record ProjectRequest(@NotBlank @Size(max = 256) String name) {}

  public record MemberRequest(@NotBlank String userId) {}

  public record ChatRequest(
      @NotBlank String projectId, @NotBlank @Size(max = 4000) String message) {}

  public record ReviewRequest(
      @NotBlank String projectId,
      @NotEmpty @Size(max = 20) List<String> documentIds,
      @NotBlank @Size(min = 8, max = 128) String requestId) {}

  public record ResolveRequest(
      @Min(0) int expectedLockVersion, @NotBlank @Size(max = 1000) String reason) {}

  public record DecisionRequest(
      @NotBlank @Size(min = 8, max = 128) String commandId,
      @Min(0) int expectedLockVersion,
      @NotBlank String contentHash,
      @NotNull @Pattern(regexp = "approved|rejected") String decision,
      @NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/projects")
  public List<Map<String, Object>> projects(@AuthenticationPrincipal Jwt jwt) {
    var who = Identity.from(jwt);
    return who.admin()
        ? repo.sql().queryForList("SELECT * FROM mb_projects WHERE tenant_id=?", who.tenantId())
        : repo.sql()
            .queryForList(
                "SELECT p.* FROM mb_projects p JOIN mb_members m ON m.project_id=p.id WHERE"
                    + " m.user_id=? AND p.tenant_id=?",
                who.userId(),
                who.tenantId());
  }

  @PostMapping("/projects")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> project(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProjectRequest request) {
    var who = Identity.from(jwt);
    repo.role(who);
    String id = id();
    repo.sql().update("INSERT INTO mb_projects VALUES(?,?,?)", id, who.tenantId(), request.name());
    return repo.required("SELECT * FROM mb_projects WHERE id=?", id);
  }

  @PostMapping("/projects/{id}/members")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void member(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody MemberRequest request) {
    var who = Identity.from(jwt);
    repo.role(who);
    repo.project(who, id);
    repo.required(
        "SELECT id FROM mb_users WHERE id=? AND tenant_id=?", request.userId(), who.tenantId());
    if (repo.one(
            "SELECT user_id FROM mb_members WHERE user_id=? AND project_id=?", request.userId(), id)
        == null) repo.sql().update("INSERT INTO mb_members VALUES(?,?)", id, request.userId());
  }

  @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> upload(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam("project_id") String project,
      @RequestParam(defaultValue = "R1") String version,
      @RequestParam(defaultValue = "general", name = "doc_type") String type,
      @RequestParam(defaultValue = "", name = "material_model") String model,
      @RequestParam(defaultValue = "internal") String classification,
      @RequestParam MultipartFile file) {
    return documents.upload(
        Identity.from(jwt), project, version, type, model, classification, file);
  }

  @GetMapping("/documents")
  @SuppressWarnings("unchecked")
  public ResponseEntity<List<Map<String,Object>>> documents(
      @AuthenticationPrincipal Jwt jwt,@RequestParam("project_id") String project,
      @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size) {
    var result=catalog.page(Identity.from(jwt),project,null,null,null,page,size);
    return ResponseEntity.ok()
        .header("X-Total-Count",result.get("total").toString())
        .header("X-Page",Long.toString(page))
        .header("X-Page-Size",Long.toString(size))
        .body((List<Map<String,Object>>)result.get("items"));
  }

  @GetMapping("/documents/{id}")
  public Map<String, Object> document(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    var row = new LinkedHashMap<>(repo.document(Identity.from(jwt), id));
    row.remove("object_path");
    return row;
  }

  @PostMapping("/documents/{id}/index")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> index(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    var who = Identity.from(jwt);
    var task = tasks.index(who, id);
    dispatch.start(task);
    return taskResponse(who, text(task, "id"));
  }

  @GetMapping("/tasks/{id}")
  public Map<String, Object> task(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return taskResponse(Identity.from(jwt), id);
  }

  @PostMapping("/tasks/{id}/cancel")
  public Map<String, Object> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    var who = Identity.from(jwt);
    tasks.cancel(who, id);
    return taskResponse(who, id);
  }

  @GetMapping("/documents/search")
  public Map<String, Object> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam("project_id") String project,
      @RequestParam String query,
      @RequestParam(required = false, name = "material_model") String model,
      @RequestParam(required = false) String version,
      @RequestParam(required = false, name = "doc_type") String type) {
    return Map.of(
        "results",
        retrieval.search(
            Identity.from(jwt),
            project,
            query,
            new RetrievalService.Filters(model, version, type),
            10,
            true),
        "retrieval_mode",
        (cfg.vectorEnabled() ? "sql_lexical_pgvector_rrf" : "sql_lexical")
            + ((cfg.rerankerModel() != null && !cfg.rerankerModel().isBlank()) ? "_reranker" : ""));
  }

  @PostMapping("/chat")
  public Map<String, Object> chat(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest request) {
    return agents.answer(Identity.from(jwt), request.projectId(), request.message());
  }

  @PostMapping("/package-reviews")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> review(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReviewRequest request) {
    var who = Identity.from(jwt);
    var task = reviews.create(who, request.projectId(), request.documentIds(), request.requestId());
    dispatch.start(task);
    return taskResponse(who, text(task, "id"));
  }

  @GetMapping("/package-reviews/{id}")
  public Map<String, Object> review(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
    return reviews.view(Identity.from(jwt), id);
  }

  @PatchMapping("/package-reviews/{id}/issues/{issue}")
  public Map<String, Object> resolve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @PathVariable String issue,
      @Valid @RequestBody ResolveRequest request) {
    return reviews.resolve(
        Identity.from(jwt), id, issue, request.expectedLockVersion(), request.reason());
  }

  @PostMapping("/package-reviews/{id}/decision")
  public Map<String, Object> decide(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @Valid @RequestBody DecisionRequest request) {
    var result =
        reviews.decide(
            Identity.from(jwt),
            id,
            request.commandId(),
            request.expectedLockVersion(),
            request.contentHash(),
            request.decision(),
            request.reason());
    return result;
  }

  @GetMapping("/package-reviews/{id}/export")
  public ResponseEntity<byte[]> export(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String id,
      @RequestParam(defaultValue = "md") String format) {
    byte[] bytes = reports.export(Identity.from(jwt), id, format);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"review-" + id + "." + format + "\"")
        .contentType(
            MediaType.parseMediaType(
                "docx".equals(format)
                    ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    : "text/markdown;charset=UTF-8"))
        .body(bytes);
  }

  private Map<String, Object> taskResponse(Identity who, String id) {
    var row = new LinkedHashMap<>(tasks.get(who, id));
    row.put("execution_mode", cfg.execution());
    return row;
  }
}
