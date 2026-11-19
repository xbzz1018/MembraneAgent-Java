package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55438/membrane_java?currentSchema=rabbit_check,public",
    "spring.datasource.username=java_check", "spring.datasource.password=synthetic-java-check-only",
    "spring.flyway.schemas=rabbit_check","spring.flyway.default-schema=rabbit_check",
    "spring.data.redis.url=redis://127.0.0.1:56388/0",
    "spring.rabbitmq.host=127.0.0.1", "spring.rabbitmq.port=56738",
    "spring.rabbitmq.username=java_check", "spring.rabbitmq.password=synthetic-java-check-only",
    "app.demo=true","app.execution=rabbit","app.model-mode=rules","app.vector-enabled=false",
    "app.storage=./target/rabbit-test-objects","app.jobs.queue=membrane.java.integration",
    "app.jobs.exchange=membrane.java.integration","app.jobs.worker-enabled=false",
    "app.jobs.initial-delay-ms=3600000","app.jobs.retry-seconds=1","app.spec-cache.enabled=true",
    "app.spec-cache.ttl-seconds=2","app.jobs.project-backlog-limit=8","app.jobs.total-backlog-limit=12"})
@EnabledIfEnvironmentVariable(named="JAVA_INFRA_TESTS",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RabbitInfrastructureTest {
  @Autowired BusinessRepository repo;
  @Autowired DocumentService docs;
  @Autowired BatchService batches;
  @Autowired TaskService tasks;
  @Autowired OutboxService outbox;
  @Autowired OutboxPublisher publisher;
  @Autowired JobProcessor processor;
  @Autowired CatalogService catalog;
  @Autowired SpecificationCache cache;
  @Autowired TransactionTemplate tx;
  @Autowired StringRedisTemplate redis;
  @Autowired RabbitTemplate rabbit;
  @Autowired ConnectionFactory connection;
  @Autowired JobSettings settings;
  @Autowired RabbitListenerEndpointRegistry listeners;
  final Identity who=new Identity("admin","demo-company","ADMIN");
  RabbitAdmin admin;
  String project;

  @BeforeEach void setup() {
    listeners.stop();
    admin=new RabbitAdmin(connection);
    admin.initialize();
    admin.purgeQueue(settings.queue());
    admin.purgeQueue(settings.queue()+".dead");
    // Only this class's isolated PostgreSQL schema is cleared.
    for(String t:List.of("mb_ingest_batches","mb_commands","mb_issues","mb_tasks","mb_reviews","mb_evidence","mb_documents","mb_events"))
      repo.sql().update("DELETE FROM "+t);
    project=id();
    repo.sql().update("INSERT INTO mb_projects VALUES(?,?,?)",project,"demo-company","SYNTHETIC Rabbit check");
  }
  @AfterEach void stop() { listeners.stop(); }
  String upload(String version) {
    return text(docs.upload(who,project,version,"spec","SYN-T100","internal",
        new MockMultipartFile("file","synthetic.txt","text/plain",
            ("SYNTHETIC SYN-T100 "+version+"\n厚度：1.0 mm").getBytes(StandardCharsets.UTF_8))),"id");
  }
  String task(String d) { return text(tasks.index(who,d),"id"); }
  String state(String t) {return text(tasks.get(who,t),"status");}

  @Test void actualBrokerProcessesBatchAndDuplicateDoesNotDuplicateEvidence() {
    String a=upload("R1"),b=upload("R2");
    var batch=batches.submit(who,project,List.of(a,b),"actual-broker-batch");
    assertThat(batch.get("status")).isEqualTo("processing");
    listeners.start();
    publisher.tick();
    await().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
      assertThat(batches.view(who,text(batch,"id")).get("status")).isEqualTo("completed");
    });
    rabbit.convertAndSend(settings.exchange(),"process",json(Map.of("task_id","ingest-"+a,"generation",0)));
    listeners.stop(); // waits for in-flight work; a later duplicate is also checked by claim below.
    assertThat(outbox.claim("ingest-"+a,0,60000,3)).isNull();
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_evidence WHERE project_id=?",Long.class,project)).isEqualTo(2);
    assertThat(repo.sql().queryForObject("SELECT SUM(attempt) FROM mb_tasks WHERE project_id=?",Long.class,project)).isEqualTo(2);
    var page=catalog.page(who,project,"SYN-T100","R1","spec",1,10);
    assertThat(((Number)page.get("total")).longValue()).isEqualTo(1);
    assertThat(((List<?>)page.get("items"))).hasSize(1);
  }
  @Test void unroutableMessageRemainsPendingEvenWhenBrokerAcknowledgesPublish() {
    String id=task(upload("R1"));
    String exchange="membrane.java.unroutable."+BusinessSupport.id();
    admin.declareExchange(new DirectExchange(exchange,true,false)); // intentionally no queue binding
    var config=new JobSettings(settings.queue(),exchange,1,1,3,60,1,600,0);
    try {
      assertThat(new OutboxPublisher(outbox,rabbit,config).publishOne()).isTrue();
      var row=repo.required("SELECT * FROM mb_task_outbox WHERE task_id=?",id);
      assertThat(row.get("status")).isEqualTo("pending");
      assertThat(row.get("last_error")).isEqualTo("broker_delivery_failed");
      repo.sql().update("UPDATE mb_task_outbox SET available_at=0 WHERE task_id=?",id);
      publisher.publishOne();
      assertThat(repo.required("SELECT status FROM mb_task_outbox WHERE task_id=?",id).get("status")).isEqualTo("sent");
    } finally { admin.deleteExchange(exchange); }
  }
  @Test void databaseOutageNacksRealDeliveryForRedelivery() throws Exception {
    rabbit.convertAndSend(settings.exchange(),"process",json(Map.of("task_id","job","generation",0)));
    var failedStore=org.mockito.Mockito.mock(OutboxService.class);
    org.mockito.Mockito.when(failedStore.claim("job",0,60000,3,600000))
        .thenThrow(new IllegalStateException("synthetic database failure"));
    var worker=new RabbitJobWorker(failedStore,org.mockito.Mockito.mock(JobProcessor.class),
        settings,new com.fasterxml.jackson.databind.ObjectMapper());
    try {
      var broker=connection.createConnection();
      var channel=broker.createChannel(false);
      try {
        final com.rabbitmq.client.GetResponse[] message=new com.rabbitmq.client.GetResponse[1];
        await().atMost(Duration.ofSeconds(5)).until(()->{
          message[0]=channel.basicGet(settings.queue(),false);return message[0]!=null;
        });
        var props=new MessageProperties();
        props.setDeliveryTag(message[0].getEnvelope().getDeliveryTag());
        worker.consume(new Message(message[0].getBody(),props),channel);
        final com.rabbitmq.client.GetResponse[] redelivery=new com.rabbitmq.client.GetResponse[1];
        await().atMost(Duration.ofSeconds(5)).until(()->{
          redelivery[0]=channel.basicGet(settings.queue(),false);return redelivery[0]!=null;
        });
        assertThat(redelivery[0].getEnvelope().isRedeliver()).isTrue();
        channel.basicAck(redelivery[0].getEnvelope().getDeliveryTag(),false);
      } finally {channel.close();broker.close();}
    } finally {worker.close();}
  }

  @Test void invalidEnvelopeGoesToDeadLetterQueue() {
    listeners.start();
    rabbit.convertAndSend(settings.exchange(),"process","{\"task_id\":\"invalid\",\"generation\":1.5}");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(()->{
      var props=admin.getQueueProperties(settings.queue()+".dead");
      assertThat(((Number)props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue()).isEqualTo(1);
    });
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_tasks",Long.class)).isZero();
  }
  @Test void postgresConcurrentBatchAndRollbackKeepOutboxAtomic() throws Exception {
    String d=upload("R1");
    assertThatThrownBy(()->batches.submit(who,project,List.of(d,"zz-missing"),"pg-rollback-key"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_task_outbox",Long.class)).isZero();
    var pool=Executors.newFixedThreadPool(4);
    var gate=new CountDownLatch(1);
    try {
      List<Future<Map<String,Object>>> fs=new ArrayList<>();
      for(int i=0;i<4;i++)fs.add(pool.submit(()->{gate.await();return batches.submit(who,project,List.of(d),"pg-concurrent-key");}));
      gate.countDown();
      Set<Object> ids=new HashSet<>();
      for(var f:fs)ids.add(f.get(15,TimeUnit.SECONDS).get("id"));
      assertThat(ids).hasSize(1);
      assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_tasks",Long.class)).isEqualTo(1);
      assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_task_outbox",Long.class)).isEqualTo(1);
    } finally {pool.shutdownNow();}
  }
  @Test void postgresRecoveryRequeuesExpiredOwnerAndFencesItsLateResult() {
    String id=task(upload("R1"));
    var old=outbox.claim(id,0,60000,3);
    repo.sql().update("UPDATE mb_tasks SET lease_until=0 WHERE id=?",id);
    outbox.recover(3);
    var current=outbox.claim(id,0,60000,3);
    assertThat(current.attempt()).isEqualTo(2);
    assertThatThrownBy(()->processor.processClaimed(id,old.token())).isInstanceOf(ResponseStatusException.class);
    processor.processClaimed(id,current.token());
    assertThat(state(id)).isEqualTo("completed");
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_evidence",Long.class)).isEqualTo(1);
  }
  @Test void redisCacheHonorsScopeExpiryCommitAndRollback() {
    String id=task(upload("R1"));
    assertThat(catalog.specifications(who,project,"SYN-T100","R1").get("cache")).isEqualTo("miss");
    assertThat(catalog.specifications(who,project,"SYN-T100","R1").get("cache")).isEqualTo("hit");
    var claim=outbox.claim(id,0,60000,3);
    processor.processClaimed(id,claim.token()); // commit invalidates the previously cached empty list
    var loaded=catalog.specifications(who,project,"SYN-T100","R1");
    assertThat(loaded.get("cache")).isEqualTo("miss");
    assertThat((List<?>)loaded.get("results")).hasSize(1);
    assertThat(catalog.specifications(who,project,"SYN-T100","R1").get("cache")).isEqualTo("hit");
    tx.executeWithoutResult(s->{cache.invalidateAfterCommit(project,"SYN-T100","R1");s.setRollbackOnly();});
    assertThat(catalog.specifications(who,project,"SYN-T100","R1").get("cache")).isEqualTo("hit");
    assertThatThrownBy(()->catalog.specifications(new Identity("outside","other","ADMIN"),project,"SYN-T100","R1"))
        .isInstanceOf(ResponseStatusException.class);
    String another=BusinessSupport.id();
    repo.sql().update("INSERT INTO mb_projects VALUES(?,?,?)",another,"demo-company","SYNTHETIC other");
    assertThat((List<?>)catalog.specifications(who,another,"SYN-T100","R1").get("results")).isEmpty();
    String key=cache.key(project,"SYN-T100","R1");
    await().atMost(Duration.ofSeconds(5)).until(()->!Boolean.TRUE.equals(redis.hasKey(key)));
    assertThat(catalog.specifications(who,project,"SYN-T100","R1").get("cache")).isEqualTo("miss");
    tx.executeWithoutResult(s->cache.invalidateAfterCommit(project,"SYN-T100","R1"));
    assertThat(redis.hasKey(key)).isFalse();
  }
  @Test void concurrentPublishersShareWindowAndAnotherProjectCanDispatch() throws Exception {
    for(int i=0;i<6;i++) task(upload("SLOT-"+i));
    var gate=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> callers=new ArrayList<>();
      for(int i=0;i<4;i++) callers.add(pool.submit(()->{
        gate.await();
        for(int j=0;j<4;j++) { var d=outbox.next(15000,2); if(d!=null) outbox.published(d); }
        return null;
      }));
      gate.countDown(); for(var f:callers) f.get(10,TimeUnit.SECONDS);
      var sent=repo.sql().queryForList("SELECT task_id FROM mb_task_outbox WHERE status='sent'");
      assertThat(sent).hasSize(2);
      assertThat(outbox.next(15000,2)).isNull();
      String other=id();
      repo.sql().update("INSERT INTO mb_projects VALUES(?,?,?)",other,"demo-company","SYNTHETIC other");
      String original=project; project=other;
      String urgent; try { urgent=task(upload("URGENT")); } finally { project=original; }
      assertThat(outbox.next(15000,2).taskId()).isEqualTo(urgent);
      tasks.cancel(who,text(sent.get(0),"task_id"));
      assertThat(outbox.next(15000,2)).isNotNull();
      assertThat(outbox.next(15000,2)).isNull();
    } finally { pool.shutdownNow(); }
  }

  @Test void backlogRollbackAndReplayPreserveWholeBatches() {
    var docs=new ArrayList<String>();
    for(int i=0;i<10;i++) docs.add(upload("BACKLOG-"+i));
    var initial=batches.submit(who,project,docs.subList(0,7),"initial-backlog");
    assertThatThrownBy(()->batches.submit(who,project,docs.subList(7,9),"atomic-backlog"))
        .isInstanceOf(IngestAdmission.Full.class);
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_ingest_batches",Long.class)).isEqualTo(1);
    assertThat(repo.sql().queryForObject("SELECT COUNT(*) FROM mb_task_outbox",Long.class)).isEqualTo(7);
    task(docs.get(7));
    assertThat(batches.submit(who,project,docs.subList(0,7),"initial-backlog").get("id")).isEqualTo(initial.get("id"));
    assertThatThrownBy(()->task(docs.get(8))).isInstanceOf(IngestAdmission.Full.class);
    tasks.cancel(who,"ingest-"+docs.get(0));
    assertThat(task(docs.get(8))).isEqualTo("ingest-"+docs.get(8));
  }

  @Test void failedIngestRetryCannotBypassAdmissionButAcceptedReplayWorks() {
    String failed=task(upload("BAD"));
    var first=outbox.claim(failed,0,60000,3);
    outbox.failed(first,false,3,0,"synthetic");
    var others=new ArrayList<String>(); for(int i=0;i<8;i++) others.add(task(upload("FILL-"+i)));
    assertThatThrownBy(()->outbox.retry(who,failed,0)).isInstanceOf(IngestAdmission.Full.class);
    assertThat(state(failed)).isEqualTo("failed");
    tasks.cancel(who,others.get(0));
    assertThat(outbox.retry(who,failed,0).get("generation")).isEqualTo(1);
    assertThat(outbox.retry(who,failed,0).get("generation")).isEqualTo(1);
  }

  @Test void expiredSendCanRecoverAtFullWindowAndFailedSendReleasesSlot() {
    task(upload("REDELIVER")); task(upload("SECOND"));
    var first=outbox.next(15000,1);
    assertThat(outbox.next(15000,1)).isNull();
    repo.sql().update("UPDATE mb_task_outbox SET send_lease=0 WHERE task_id=?",first.taskId());
    var recovered=outbox.next(15000,1);
    assertThat(recovered.taskId()).isEqualTo(first.taskId());
    assertThat(recovered.sendToken()).isNotEqualTo(first.sendToken());
    outbox.published(first); // stale confirm cannot steal the new reservation
    outbox.publishFailed(recovered,60000);
    var second=outbox.next(15000,1);
    assertThat(second.taskId()).isNotEqualTo(first.taskId());
    assertThat(outbox.next(15000,1)).isNull();
  }

  @Test void publisherSkipsAnotherTransactionsLockedRow() throws Exception {
    String locked=task(upload("LOCKED")), available=task(upload("AVAILABLE"));
    repo.sql().update("UPDATE mb_task_outbox SET available_at=0 WHERE task_id=?",locked);
    var held=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(2);
    try {
      var owner=pool.submit(()->tx.executeWithoutResult(s->{
        repo.required("SELECT task_id FROM mb_task_outbox WHERE task_id=? FOR UPDATE",locked);
        held.countDown();
        try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test lock timed out"); }
        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
      }));
      assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
      var other=pool.submit(()->outbox.next(15000));
      assertThat(other.get(2,TimeUnit.SECONDS).taskId()).isEqualTo(available);
      release.countDown();
      owner.get(5,TimeUnit.SECONDS);
      assertThat(outbox.next(15000).taskId()).isEqualTo(locked);
    } finally { release.countDown();pool.shutdownNow(); }
  }

}
