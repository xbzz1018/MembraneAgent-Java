package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:batch-outbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo=true", "app.execution=rabbit", "app.model-mode=rules", "app.vector-enabled=false",
    "app.storage=./target/batch-test-objects", "app.jobs.worker-enabled=false",
    "app.jobs.initial-delay-ms=3600000", "app.spec-cache.enabled=false"})
@AutoConfigureMockMvc
class BatchOutboxTest {
  @Autowired BusinessRepository repo;
  @Autowired BatchService batches;
  @Autowired TaskService tasks;
  @Autowired OutboxService outbox;
  @Autowired DocumentService documents;
  @Autowired CatalogService catalog;
  @Autowired JobProcessor processor;
  @Autowired PlatformSettings cfg;
  @Autowired TransactionTemplate tx;
  @Autowired MockMvc mvc;
  final Identity who=new Identity("designer","demo-company","DESIGNER");

  @BeforeEach void clean() {
    for(String t:List.of("mb_ingest_batches","mb_commands","mb_issues","mb_tasks","mb_reviews","mb_evidence","mb_documents","mb_events"))
      repo.sql().update("DELETE FROM "+t);
  }
  String doc(String version) {
    return text(documents.upload(who,"demo-project",version,"spec","SYN-T100","internal",
        new MockMultipartFile("file","synthetic.txt","text/plain",
            ("SYNTHETIC SYN-T100 "+version+"\n厚度 1.0 mm").getBytes(StandardCharsets.UTF_8))),"id");
  }
  String task(String doc) { return text(tasks.index(who,doc),"id"); }
  long count(String table) { return repo.sql().queryForObject("SELECT COUNT(*) FROM "+table,Long.class); }
  String state(String id) { return text(tasks.get(who,id),"status"); }
  OutboxService.Claim claim(String id) { return outbox.claim(id,0,60000,3); }
  void finish(String id) { var claim=claim(id); assertThat(claim).isNotNull(); processor.processClaimed(id,claim.token()); }

  @Test void badFileRollsBackBatchTasksAndOutboxTogether() {
    var d=doc("R1");
    assertThatThrownBy(()->batches.submit(who,"demo-project",List.of(d,"zz-missing"),"rollback-request"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(count("mb_ingest_batches")).isZero();
    assertThat(count("mb_tasks")).isZero();
    assertThat(count("mb_task_outbox")).isZero();
    assertThat(count("mb_documents")).isEqualTo(1);
  }
  @Test void duplicateBatchReusesTasksAndConflictingPayloadIsRejected() {
    String a=doc("R1"), b=doc("R2");
    var first=batches.submit(who,"demo-project",List.of(a,b),"same-request");
    var repeat=batches.submit(who,"demo-project",List.of(b,a,a),"same-request");
    assertThat(repeat.get("id")).isEqualTo(first.get("id"));
    assertThat(count("mb_tasks")).isEqualTo(2);
    assertThat(count("mb_task_outbox")).isEqualTo(2);
    assertThatThrownBy(()->batches.submit(who,"demo-project",List.of(a),"same-request"))
        .isInstanceOf(ResponseStatusException.class);
    finish("ingest-"+a); finish("ingest-"+b);
    var progress=batches.view(who,text(first,"id"));
    assertThat(progress.get("status")).isEqualTo("completed");
    assertThat(progress.get("finished")).isEqualTo(2L);
    assertThat(count("mb_evidence")).isEqualTo(2);
  }
  @Test void concurrentSubmissionsCreateOneBatchAndOneTaskPerFile() throws Exception {
    String a=doc("R1"), b=doc("R2");
    var pool=Executors.newFixedThreadPool(4);
    var start=new CountDownLatch(1);
    try {
      List<Future<Map<String,Object>>> results=new ArrayList<>();
      for(int i=0;i<4;i++) results.add(pool.submit(()->{start.await();return batches.submit(who,"demo-project",List.of(a,b),"parallel-request");}));
      start.countDown();
      Set<Object> ids=new HashSet<>();
      for(var f:results) ids.add(f.get(10,TimeUnit.SECONDS).get("id"));
      assertThat(ids).hasSize(1);
      assertThat(count("mb_tasks")).isEqualTo(2);
      assertThat(count("mb_task_outbox")).isEqualTo(2);
    } finally { pool.shutdownNow(); }
  }
  @Test void failedSendCanBeRepublishedButStaleConfirmationCannotEraseRetry() {
    String id=task(doc("R1"));
    var first=outbox.next(15000);
    assertThat(first.taskId()).isEqualTo(id);
    outbox.publishFailed(first,0);
    var second=outbox.next(15000);
    assertThat(second.sendToken()).isNotEqualTo(first.sendToken());
    outbox.published(first);
    assertThat(text(repo.required("SELECT * FROM mb_task_outbox WHERE task_id=?",id),"status")).isEqualTo("publishing");
    var claim=claim(id);
    outbox.failed(claim,true,3,0,"synthetic_timeout");
    outbox.published(second); // Consumer may finish before publisher receives confirm.
    assertThat(text(repo.required("SELECT * FROM mb_task_outbox WHERE task_id=?",id),"status")).isEqualTo("pending");
    assertThat(outbox.next(15000)).isNotNull();
  }
  @Test void duplicateDeliveryDoesNotReexecuteCompletedOrActiveTask() {
    String id=task(doc("R1"));
    var claim=claim(id);
    assertThat(claim(id)).isNull();
    processor.processClaimed(id,claim.token());
    assertThat(claim(id)).isNull();
    assertThat(state(id)).isEqualTo("completed");
    assertThat(count("mb_evidence")).isEqualTo(1);
    assertThat(text(repo.required("SELECT * FROM mb_task_outbox WHERE task_id=?",id),"status")).isEqualTo("done");
  }
  @Test void finiteRetriesAndManualRetryRejectOldGeneration() {
    String id=task(doc("R1"));
    OutboxService.Claim last=null;
    for(int i=1;i<=3;i++) {
      last=claim(id);
      assertThat(last.attempt()).isEqualTo(i);
      outbox.failed(last,true,3,0,"synthetic_timeout");
    }
    assertThat(state(id)).isEqualTo("failed");
    assertThat(claim(id)).isNull();
    var retried=outbox.retry(who,id,0);
    assertThat(retried.get("generation")).isEqualTo(1);
    assertThat(outbox.retry(who,id,0).get("generation")).isEqualTo(1);
    assertThat(outbox.claim(id,0,60000,3)).isNull();
    var current=outbox.claim(id,1,60000,3);
    assertThat(current.attempt()).isEqualTo(1);
    assertThat(current.token()).isNotEqualTo(last.token());
    processor.processClaimed(id,current.token());
    assertThat(state(id)).isEqualTo("completed");
  }
  @Test void invalidDocumentIsTerminalWithoutRetry() {
    String id=task(doc("R1"));
    outbox.failed(claim(id),false,3,0,"invalid_document");
    assertThat(state(id)).isEqualTo("failed");
    assertThat(outbox.next(15000)).isNull();
  }
  @Test void expiredLeaseIsRecoveredAndOldClaimCannotWriteOrRenew() {
    String id=task(doc("R1"));
    var old=claim(id);
    repo.sql().update("UPDATE mb_tasks SET lease_until=0 WHERE id=?",id);
    assertThat(outbox.renew(old,60000)).isFalse();
    outbox.recover(3);
    var replacement=claim(id);
    assertThat(replacement.attempt()).isEqualTo(2);
    assertThatThrownBy(()->processor.processClaimed(id,old.token())).isInstanceOf(ResponseStatusException.class);
    assertThat(count("mb_evidence")).isZero();
    outbox.failed(old,false,3,0,"late_failure");
    assertThat(state(id)).isEqualTo("running");
    processor.processClaimed(id,replacement.token());
    assertThat(state(id)).isEqualTo("completed");
  }
  @Test void cancellationFencesLateResultAndLeavesOtherDocumentVersionUntouched() {
    String v1=doc("R1"), v2=doc("R2");
    String t1=task(v1), t2=task(v2);
    var old=claim(t1);
    tasks.cancel(who,t1);
    finish(t2);
    assertThatThrownBy(()->processor.processClaimed(t1,old.token())).isInstanceOf(ResponseStatusException.class);
    assertThat(state(t1)).isEqualTo("cancelled");
    assertThat(state(t2)).isEqualTo("completed");
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_evidence WHERE document_id=?",Long.class,v1)).isZero();
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_evidence WHERE document_id=?",Long.class,v2)).isEqualTo(1);
  }
  @Test void retryDelayAndPublisherLeaseUseDurableTime() {
    String id=task(doc("R1"));
    var clock=Clock.fixed(Instant.now(),ZoneOffset.UTC);
    var service=new OutboxService(repo,tx,cfg,clock);
    var delivery=service.next(1000);
    assertThat(delivery).isNotNull();
    assertThat(service.next(1000)).isNull();
    var later=new OutboxService(repo,tx,cfg,Clock.offset(clock,Duration.ofSeconds(2)));
    assertThat(later.next(1000)).isNotNull();
    var claim=later.claim(id,0,60000,3);
    later.failed(claim,true,3,10000,"timeout");
    assertThat(later.claim(id,0,60000,3)).isNull();
    assertThat(later.next(1000)).isNull();
    var retryTime=new OutboxService(repo,tx,cfg,Clock.offset(clock,Duration.ofSeconds(13)));
    assertThat(retryTime.claim(id,0,60000,3)).isNotNull();
  }
  @Test void pagingLoadsAssociatedStateAndCountsAndKeepsProjectScope() {
    String a=doc("R1"), b=doc("R2");finish(task(a));task(b);
    var result=catalog.page(who,"demo-project","SYN-T100",null,"spec",1,1);
    assertThat(((Number)result.get("total")).longValue()).isEqualTo(2);
    assertThat((List<?>)result.get("items")).hasSize(1);
    var exact=catalog.page(who,"demo-project","SYN-T100","R1","spec",1,20);
    var row=((List<Map<String,Object>>)exact.get("items")).get(0);
    assertThat(row.get("id")).isEqualTo(a);
    assertThat(((Number)row.get("chunk_count")).longValue()).isEqualTo(1);
    assertThat(((Map<?,?>)row.get("processing")).get("status")).isEqualTo("completed");
    assertThatThrownBy(()->catalog.page(new Identity("outsider","other-company","ADMIN"),"demo-project",null,null,null,1,20))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(()->catalog.page(who,"demo-project",null,null,null,1,101))
        .isInstanceOf(ResponseStatusException.class);
  }
  @Test void batchHttpContractReturnsAcceptedAndHidesInternalClaim() throws Exception {
    String d=doc("R1");
    var auth=jwt().jwt(j->j.subject("designer").claim("tenant_id","demo-company").claim("role","DESIGNER"));
    mvc.perform(post("/api/v1/ingest-batches").with(auth).contentType("application/json")
        .content(json(Map.of("project_id","demo-project","document_ids",List.of(d),"request_id","http-batch-request"))))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("processing"))
        .andExpect(jsonPath("$.items[0].business_version").value("R1"));
    claim("ingest-"+d);
    mvc.perform(get("/api/v1/tasks/ingest-"+d).with(auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.claim_token").doesNotExist()).andExpect(jsonPath("$.lease_until").doesNotExist());
  }
  @Test void persistedDeadlineFencesResultBeforeHeartbeatAndManualRetryGetsFreshAttempt() {
    String d=doc("R1"), id=task(d);
    var claim=outbox.claim(id,0,60000,3,600000);
    repo.sql().update("UPDATE mb_tasks SET deadline_at=0 WHERE id=?",id);
    assertThat(outbox.renew(claim,60000)).isFalse();
    assertThatThrownBy(()->processor.processClaimed(id,claim.token()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(count("mb_evidence")).isZero();
    outbox.recover(3);
    assertThat(state(id)).isEqualTo("failed");
    assertThat(tasks.get(who,id).get("last_error")).isEqualTo("processing_deadline_exceeded");
    outbox.retry(who,id,0);
    assertThat(repo.required("SELECT status FROM mb_documents WHERE id=?",d).get("status")).isEqualTo("uploaded");
    var fresh=outbox.claim(id,1,60000,3,600000);
    assertThat(fresh.attempt()).isEqualTo(1);
    processor.processClaimed(id,fresh.token());
    assertThat(state(id)).isEqualTo("completed");
  }

  @Test void leaseRenewalCannotExtendPersistedExecutionDeadline() {
    String id=task(doc("R1"));
    var clock=Clock.fixed(Instant.now(),ZoneOffset.UTC);
    var service=new OutboxService(repo,tx,cfg,clock);
    var claim=service.claim(id,0,60000,3,10000);
    var initial=repo.required("SELECT lease_until,deadline_at FROM mb_tasks WHERE id=?",id);
    assertThat(initial.get("lease_until")).isEqualTo(initial.get("deadline_at"));
    var later=new OutboxService(repo,tx,cfg,Clock.offset(clock,Duration.ofSeconds(5)));
    assertThat(later.renew(claim,60000)).isTrue();
    assertThat(repo.required("SELECT lease_until FROM mb_tasks WHERE id=?",id).get("lease_until"))
        .isEqualTo(initial.get("deadline_at"));
    var expired=new OutboxService(repo,tx,cfg,Clock.offset(clock,Duration.ofSeconds(11)));
    assertThat(expired.renew(claim,60000)).isFalse();
    assertThat(expired.claim(id,0,60000,3,10000)).isNull();
    assertThat(state(id)).isEqualTo("failed");
  }

  @Autowired DocumentParser parser;
  @Autowired ReviewService reviews;
  @Autowired ModelGateway model;
  @Autowired RetrievalService retrieval;
  @Autowired SpecificationCache cache;

  @Test void expirationDuringFinalWritesRollsBackEvidenceAndDocumentPublication() {
    String d=doc("R1"), id=task(d);
    var claim=claim(id);
    var checkedTasks=org.mockito.Mockito.spy(tasks);
    var checks=new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(invocation->{
      // Inject expiry exactly between result writes and final ownership validation.
      if(checks.incrementAndGet()==2) repo.sql().update("UPDATE mb_tasks SET deadline_at=0 WHERE id=?",id);
      return invocation.callRealMethod();
    }).when(checkedTasks).assertClaim(id,claim.token());
    var checkedProcessor=new JobProcessor(repo,checkedTasks,documents,parser,reviews,model,cfg,tx,retrieval,cache);
    assertThatThrownBy(()->checkedProcessor.processClaimed(id,claim.token()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(checks.get()).isEqualTo(2);
    assertThat(count("mb_evidence")).isZero();
    assertThat(repo.required("SELECT status FROM mb_documents WHERE id=?",d).get("status")).isEqualTo("indexing");
    assertThat(state(id)).isEqualTo("running");
  }

  @Test void legacyDocumentListIsBoundedAndAdvertisesPagination() throws Exception {
    for(int i=0;i<21;i++) doc("R"+i);
    var auth=jwt().jwt(j->j.subject("designer").claim("tenant_id","demo-company").claim("role","DESIGNER"));
    mvc.perform(get("/api/v1/documents").with(auth).param("project_id","demo-project"))
        .andExpect(status().isOk()).andExpect(header().string("X-Total-Count","21"))
        .andExpect(header().string("X-Page-Size","20")).andExpect(jsonPath("$.length()").value(20));
    mvc.perform(get("/api/v1/documents").with(auth).param("project_id","demo-project").param("page","2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/v1/documents").with(auth).param("project_id","demo-project").param("size","101"))
        .andExpect(status().isUnprocessableEntity());
  }

}
