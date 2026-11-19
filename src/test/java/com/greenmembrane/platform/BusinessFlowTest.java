package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:business;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "app.execution=inline",
      "app.demo=true",
      "app.model-mode=rules",
      "app.vector-enabled=false"
    })
@AutoConfigureMockMvc
class BusinessFlowTest {
  static final Path OBJECTS;

  static {
    try {
      OBJECTS = Files.createTempDirectory("membrane-java-test-");
    } catch (IOException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("app.storage", OBJECTS::toString);
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired BusinessRepository repo;
  @Autowired ReviewService reviews;
  @Autowired TaskService tasks;
  @Autowired JobProcessor processor;
  @Autowired TransactionTemplate tx;
  String designer, reviewer, employee;
  final Identity designIdentity = new Identity("designer", "demo-company", "DESIGNER");
  final Identity reviewIdentity = new Identity("reviewer", "demo-company", "REVIEWER");
  final String design = "SYNTHETIC 停车棚设计\n型号 SYN-T100\n厚度：1.0 mm\n抗拉强度：3500 N/5cm";
  final String spec = "SYNTHETIC 材料规格\n型号 SYN-T100\n厚度：1.0 mm\n抗拉强度不低于 4000 N/5cm";

  @BeforeEach
  void reset() throws Exception {
    for (String table :
        List.of(
            "mb_commands",
            "mb_issues",
            "mb_tasks",
            "mb_reviews",
            "mb_evidence",
            "mb_documents",
            "mb_events")) repo.sql().update("DELETE FROM " + table);
    designer = login("designer");
    reviewer = login("reviewer");
    employee = login("employee");
  }

  String login(String name) throws Exception {
    return node(mvc.perform(
                post("/api/v1/auth/login")
                    .contentType("application/json")
                    .content(json(Map.of("username", name, "password", "local-demo-change-me"))))
            .andExpect(status().isOk())
            .andReturn())
        .path("access_token")
        .asText();
  }

  JsonNode node(MvcResult result) throws Exception {
    return mapper.readTree(result.getResponse().getContentAsByteArray());
  }

  JsonNode upload(String token, String body, String version, String type, int expected)
      throws Exception {
    return node(
        mvc.perform(
                multipart("/api/v1/documents/upload")
                    .file(
                        new MockMultipartFile(
                            "file",
                            "synthetic.txt",
                            "text/plain",
                            body.getBytes(StandardCharsets.UTF_8)))
                    .param("project_id", "demo-project")
                    .param("version", version)
                    .param("doc_type", type)
                    .param("material_model", "SYN-T100")
                    .param("classification", "internal")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().is(expected))
            .andReturn());
  }

  String indexed(String body, String version, String type) throws Exception {
    String id = upload(designer, body, version, type, 201).path("id").asText();
    mvc.perform(
            post("/api/v1/documents/" + id + "/index")
                .header("Authorization", "Bearer " + designer))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("completed"));
    return id;
  }

  JsonNode createReview(List<String> ids, String key) throws Exception {
    return node(
        mvc.perform(
                post("/api/v1/package-reviews")
                    .header("Authorization", "Bearer " + designer)
                    .contentType("application/json")
                    .content(
                        json(
                            Map.of(
                                "project_id",
                                "demo-project",
                                "document_ids",
                                ids,
                                "request_id",
                                key))))
            .andExpect(status().isAccepted())
            .andReturn());
  }

  JsonNode view(String review) throws Exception {
    return node(
        mvc.perform(
                get("/api/v1/package-reviews/" + review)
                    .header("Authorization", "Bearer " + reviewer))
            .andExpect(status().isOk())
            .andReturn());
  }

  Map<String, Object> decision(String command, int version, String hash, String reason) {
    return Map.of(
        "command_id",
        command,
        "expected_lock_version",
        version,
        "content_hash",
        hash,
        "decision",
        "approved",
        "reason",
        reason);
  }

  ResultActions decide(String review, Map<String, Object> body) throws Exception {
    return mvc.perform(
        post("/api/v1/package-reviews/" + review + "/decision")
            .header("Authorization", "Bearer " + reviewer)
            .contentType("application/json")
            .content(json(body)));
  }

  @Test
  void fullReviewIsVersionedIdempotentAndExportable() throws Exception {
    String d = indexed(design, "R1", "design"), s = indexed(spec, "S2", "spec");
    String review = createReview(List.of(d, s), "review-request-001").path("target_id").asText();
    JsonNode v = view(review);
    assertThat(v.path("status").asText()).isEqualTo("awaiting_approval");
    assertThat(v.path("issues")).hasSize(1);
    String hash = v.path("content_hash").asText();
    decide(review, decision("approval-0001", 1, hash, "checked")).andExpect(status().isConflict());
    mvc.perform(
            get("/api/v1/package-reviews/" + review + "/export")
                .header("Authorization", "Bearer " + reviewer))
        .andExpect(status().isConflict());
    String issue = v.path("issues").path(0).path("id").asText();
    mvc.perform(
            patch("/api/v1/package-reviews/" + review + "/issues/" + issue)
                .header("Authorization", "Bearer " + reviewer)
                .contentType("application/json")
                .content(json(Map.of("expected_lock_version", 1, "reason", "已联系设计人员核对，该项按流程人工处置"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lock_version").value(2));
    decide(review, decision("approval-0002", 1, hash, "checked")).andExpect(status().isConflict());
    var body = decision("approval-0003", 2, hash, "synthetic approval");
    JsonNode receipt = node(decide(review, body).andExpect(status().isOk()).andReturn());
    assertThat(node(decide(review, body).andExpect(status().isOk()).andReturn()))
        .isEqualTo(receipt);
    decide(review, decision("approval-0003", 2, hash, "different"))
        .andExpect(status().isConflict());
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_commands", Integer.class))
        .isEqualTo(1);
    String md =
        mvc.perform(
                get("/api/v1/package-reviews/" + review + "/export")
                    .header("Authorization", "Bearer " + reviewer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(md).contains(hash, "3500.0", "4000.0", "复核状态：resolved");
    byte[] word =
        mvc.perform(
                get("/api/v1/package-reviews/" + review + "/export")
                    .param("format", "docx")
                    .header("Authorization", "Bearer " + reviewer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (var doc = new XWPFDocument(new ByteArrayInputStream(word))) {
      assertThat(String.join("\n", doc.getParagraphs().stream().map(p -> p.getText()).toList()))
          .contains(hash, review, "3500.0");
    }
    processor.process(
        text(repo.required("SELECT * FROM mb_tasks WHERE target_id=?", review), "id"),
        "late-attempt",
        9);
    assertThat(view(review).path("status").asText()).isEqualTo("approved");
  }

  @Test
  void uploadAndIndexRetriesDoNotDuplicateEvidence() throws Exception {
    String id = indexed(design, "R1", "design");
    assertThat(upload(designer, design, "R1", "design", 201).path("id").asText()).isEqualTo(id);
    upload(designer, design, "R1", "spec", 409);
    mvc.perform(
            post("/api/v1/documents/" + id + "/index")
                .header("Authorization", "Bearer " + designer))
        .andExpect(status().isAccepted());
    assertThat(
            repo.sql()
                .queryForObject(
                    "SELECT COUNT(*) FROM mb_evidence WHERE document_id=?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void exactFiltersAndLocationsAreAppliedBeforeReturningEvidence() throws Exception {
    indexed(design, "R1", "design");
    indexed(spec, "S2", "spec");
    mvc.perform(
            get("/api/v1/documents/search")
                .param("project_id", "demo-project")
                .param("query", "SYN-T100")
                .param("version", "S2")
                .param("doc_type", "spec")
                .param("material_model", "SYN-T100")
                .header("Authorization", "Bearer " + employee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()").value(1))
        .andExpect(jsonPath("$.results[0].business_version").value("S2"))
        .andExpect(jsonPath("$.results[0].location").isNotEmpty())
        .andExpect(jsonPath("$.retrieval_mode").value("sql_lexical"));
    mvc.perform(
            get("/api/v1/documents/search")
                .param("project_id", "demo-project")
                .param("query", "SYN-T100")
                .param("material_model", "SYN-T100A")
                .header("Authorization", "Bearer " + employee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()").value(0));
  }

  @Test
  void rolesAndProjectScopesAreEnforced() throws Exception {
    upload(employee, design, "R1", "design", 403);
    String d = indexed(design, "R1", "design");
    String other = id();
    repo.sql()
        .update(
            "INSERT INTO mb_projects VALUES(?,?,?)",
            other,
            "other-company",
            "SYNTHETIC other tenant");
    mvc.perform(
            get("/api/v1/documents/search")
                .param("project_id", other)
                .param("query", "SYN-T100")
                .header("Authorization", "Bearer " + designer))
        .andExpect(status().isNotFound());
    repo.sql().update("INSERT INTO mb_projects VALUES(?,?,?)", id(), "demo-company", "unassigned");
    String r = createReview(List.of(d), "role-review-0001").path("target_id").asText();
    mvc.perform(
            post("/api/v1/package-reviews/" + r + "/decision")
                .header("Authorization", "Bearer " + designer)
                .contentType("application/json")
                .content(
                    json(
                        decision(
                            "forbidden-command", 1, view(r).path("content_hash").asText(), "bad"))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/documents/" + d)).andExpect(status().isUnauthorized());
  }

  @Test
  void missingSpecsProduceAnExplicitHumanIssue() throws Exception {
    String r =
        createReview(List.of(indexed(design, "R1", "design")), "missing-spec-0001")
            .path("target_id")
            .asText();
    assertThat(view(r).path("issues").path(0).path("summary").asText()).contains("缺少同型号");
  }

  @Test
  void reviewRequestKeyCannotBeReusedForDifferentSources() throws Exception {
    String d = indexed(design, "R1", "design"), s = indexed(spec, "S2", "spec");
    JsonNode first = createReview(List.of(d, s), "same-request-0001");
    assertThat(createReview(List.of(s, d), "same-request-0001").path("id").asText())
        .isEqualTo(first.path("id").asText());
    mvc.perform(
            post("/api/v1/package-reviews")
                .header("Authorization", "Bearer " + designer)
                .contentType("application/json")
                .content(
                    json(
                        Map.of(
                            "project_id",
                            "demo-project",
                            "document_ids",
                            List.of(d),
                            "request_id",
                            "same-request-0001"))))
        .andExpect(status().isConflict());
  }

  @Test
  void staleInlineExecutionAndCancelledExecutionCannotPublish() throws Exception {
    String doc = upload(designer, design, "R1", "design", 201).path("id").asText();
    String task = text(tasks.index(designIdentity, doc), "id");
    String first = tasks.claim(task, "inline/run", 1);
    String second = tasks.claim(task, "inline/run", 2);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> tasks.assertClaim(task, first)))
        .isInstanceOf(ResponseStatusException.class);
    tx.executeWithoutResult(s -> tasks.assertClaim(task, second));
    assertThatThrownBy(() -> tasks.claim(task, "different/run", 3))
        .isInstanceOf(ResponseStatusException.class);
    tasks.cancel(designIdentity, task);
    assertThatThrownBy(() -> tx.executeWithoutResult(s -> tasks.assertClaim(task, second)))
        .isInstanceOf(ResponseStatusException.class);
    processor.process(task, "inline/run", 3);
    tasks.fail(task);
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_evidence", Integer.class))
        .isZero();
    assertThat(text(tasks.get(designIdentity, task), "status")).isEqualTo("cancelled");
  }

  @Test
  void cancelledReviewCannotBeRepublished() throws Exception {
    String d = indexed(design, "R1", "design");
    var task = reviews.create(designIdentity, "demo-project", List.of(d), "cancel-review-001");
    String taskId = text(task, "id"), rid = text(task, "target_id");
    tasks.claim(taskId, "run", 1);
    tasks.cancel(reviewIdentity, taskId);
    processor.process(taskId, "run", 2);
    assertThat(view(rid).path("status").asText()).isEqualTo("cancelled");
    assertThat(view(rid).path("issues")).isEmpty();
  }

  @Test
  void corruptedEvidenceBlocksApproval() throws Exception {
    String same = spec.replace("4000", "3500");
    String d = indexed(design, "R1", "design"), s = indexed(same, "R2", "spec");
    String r = createReview(List.of(d, s), "corrupt-review-001").path("target_id").asText();
    JsonNode v = view(r);
    assertThat(v.path("issues")).isEmpty();
    repo.sql().update("UPDATE mb_evidence SET body='tampered' WHERE document_id=?", d);
    decide(r, decision("corrupt-approval", 1, v.path("content_hash").asText(), "checked"))
        .andExpect(status().isConflict());
  }

  @Test
  void generalChatDoesNotCreateAReview() throws Exception {
    mvc.perform(
            post("/api/v1/chat")
                .header("Authorization", "Bearer " + employee)
                .contentType("application/json")
                .content(json(Map.of("project_id", "demo-project", "message", "什么是膜结构"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.route").value("direct_chat"))
        .andExpect(jsonPath("$.mode").value("rules"));
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_reviews", Integer.class))
        .isZero();
  }

  @Test
  void revokedRoleInvalidatesTheOldToken() throws Exception {
    repo.sql().update("UPDATE mb_users SET role='BUSINESS' WHERE id='employee'");
    try {
      mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + employee))
          .andExpect(status().isUnauthorized());
    } finally {
      repo.sql().update("UPDATE mb_users SET role='EMPLOYEE' WHERE id='employee'");
    }
  }

  @Test
  void rejectedReviewRequiresANewRequestToRunAgain() throws Exception {
    String r =
        createReview(List.of(indexed(design, "R1", "design")), "reject-review-0001")
            .path("target_id")
            .asText();
    var v = view(r);
    var body =
        new HashMap<String, Object>(
            decision("reject-command-01", 1, v.path("content_hash").asText(), "资料需补充"));
    body.put("decision", "rejected");
    decide(r, body).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("rejected"));
    mvc.perform(
            get("/api/v1/package-reviews/" + r + "/export")
                .header("Authorization", "Bearer " + reviewer))
        .andExpect(status().isConflict());
    processor.process(
        text(repo.required("SELECT * FROM mb_tasks WHERE target_id=?", r), "id"), "late", 8);
    assertThat(view(r).path("status").asText()).isEqualTo("rejected");
  }

  @Test
  void chineseQueryFindsDocumentTerms() throws Exception {
    indexed(design, "R1", "design");
    mvc.perform(
            get("/api/v1/documents/search")
                .param("project_id", "demo-project")
                .param("query", "查询停车棚设计资料")
                .header("Authorization", "Bearer " + employee))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.results.length()").value(1));
  }
}
