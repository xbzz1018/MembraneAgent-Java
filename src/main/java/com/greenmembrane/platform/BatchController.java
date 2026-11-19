package com.greenmembrane.platform;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BatchController {
  private final BatchService batches;
  private final OutboxService outbox;
  private final TaskService tasks;
  private final CatalogService catalog;
  public BatchController(BatchService batches,OutboxService outbox,TaskService tasks,CatalogService catalog) {
    this.batches=batches;this.outbox=outbox;this.tasks=tasks;this.catalog=catalog;
  }
  public record BatchRequest(@NotBlank String projectId,@NotEmpty @Size(max=20) List<@NotBlank String> documentIds,
      @NotBlank @Size(min=8,max=128) String requestId) {}
  public record RetryRequest(@NotNull @Min(0) Integer expectedGeneration) {}
  @PostMapping("/ingest-batches")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String,Object> submit(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody BatchRequest request) {
    return batches.submit(Identity.from(jwt),request.projectId(),request.documentIds(),request.requestId());
  }
  @GetMapping("/ingest-batches/{id}")
  public Map<String,Object> batch(@AuthenticationPrincipal Jwt jwt,@PathVariable String id) {
    return batches.view(Identity.from(jwt),id);
  }
  @PostMapping("/ingest-batches/{id}/items/{documentId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String,Object> retryFile(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
      @PathVariable String documentId,@Valid @RequestBody RetryRequest request) {
    return batches.retryFile(Identity.from(jwt),id,documentId,request.expectedGeneration());
  }
  @PostMapping("/tasks/{id}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String,Object> retry(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody RetryRequest request) {
    var who=Identity.from(jwt);
    outbox.retry(who,id,request.expectedGeneration());
    return tasks.get(who,id);
  }
  @GetMapping("/documents/page")
  public Map<String,Object> page(@AuthenticationPrincipal Jwt jwt,@RequestParam("project_id") String project,
      @RequestParam(required=false,name="material_model") String model,@RequestParam(required=false) String version,
      @RequestParam(required=false,name="doc_type") String type,
      @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") long size) {
    return catalog.page(Identity.from(jwt),project,model,version,type,page,size);
  }
  @GetMapping("/specifications")
  public Map<String,Object> specs(@AuthenticationPrincipal Jwt jwt,@RequestParam("project_id") String project,
      @RequestParam("material_model") String model,@RequestParam String version) {
    return catalog.specifications(Identity.from(jwt),project,model,version);
  }
}
