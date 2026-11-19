package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class ModelBoundaryTest {
  HttpServer server;
  final List<String> models = new CopyOnWriteArrayList<>();
  int status = 200;
  String output = "规范检索 PVDF 1050";
  ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          var input = mapper.readTree(exchange.getRequestBody());
          models.add(input.path("model").asText());
          byte[] response =
              json(Map.of(
                      "choices",
                      List.of(
                          Map.of(
                              "finish_reason",
                              "stop",
                              "message",
                              Map.of("role", "assistant", "content", output)))))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, response.length);
          try (var body = exchange.getResponseBody()) {
            body.write(response);
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  ModelGateway gateway() {
    var cfg = mock(PlatformSettings.class);
    when(cfg.modelMode()).thenReturn("api");
    when(cfg.modelApiKey()).thenReturn("synthetic");
    when(cfg.modelBaseUrl())
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    when(cfg.modelName()).thenReturn("membrane-base");
    when(cfg.queryAdapter()).thenReturn("user");
    when(cfg.reviewAdapter()).thenReturn("shencha");
    return new ModelGateway(cfg, mapper);
  }

  @Test
  void taskSelectsTheRegisteredAdapter() {
    var gateway = gateway();
    gateway.normalize("查询 PVDF 1050");
    gateway.organize("findings", "evidence");
    gateway.chat(gateway.model(), "system", "question");
    assertThat(models).containsExactly("user", "shencha", "membrane-base");
  }

  @Test
  void modifiedModelIdentifierFallsBackToOriginalQuery() {
    output = "查询 PVDF 1050A";
    assertThat(gateway().normalize("查询 PVDF 1050")).isEqualTo("查询 PVDF 1050");
  }

  @Test
  void modelServiceFailureRemainsAFailure() {
    status = 503;
    assertThatThrownBy(() -> gateway().normalize("PVDF 1050"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(502));
  }

  @Test
  void unknownIntentIsRejectedBeforeToolExecution() {
    output = "{\"intent\":\"delete_project\"}";
    var retrieval = mock(RetrievalService.class);
    var repository = mock(BusinessRepository.class);
    var agent = new AgentService(retrieval, repository, gateway(), mapper);
    try {
      assertThatThrownBy(() -> agent.answer(new Identity("u", "t", "EMPLOYEE"), "p", "查询公司资料"))
          .isInstanceOf(ResponseStatusException.class);
    } finally {
      agent.stop();
    }
    verifyNoInteractions(retrieval);
  }

  @Test
  void slowDownstreamIsBoundedByConfiguredRequestTimeout() throws Exception {
    server.createContext("/slow",exchange -> {
      try { Thread.sleep(2500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
      exchange.close();
    });
    var cfg=mock(PlatformSettings.class);
    when(cfg.modelMode()).thenReturn("api");
    when(cfg.modelApiKey()).thenReturn("synthetic");
    when(cfg.modelBaseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
    var slow=new ModelGateway(cfg,mapper,1,1);
    long start=System.nanoTime();
    assertThatThrownBy(()->slow.request("/slow",Map.of())).isInstanceOf(ResponseStatusException.class);
    assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));
  }

  @Test
  void remoteProviderCannotUseInternalDocuments() {
    assertThat(gateway().allowsInternal()).isFalse();
  }

  @Test
  void rerankerUsesItsOwnEndpointAndPreservesEvidenceIdentity() throws Exception {
    var cfg = mock(PlatformSettings.class);
    when(cfg.modelMode()).thenReturn("api");
    when(cfg.rerankerModel()).thenReturn("rerank-test");
    when(cfg.rerankerBaseUrl())
        .thenReturn("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    when(cfg.rerankerApiKey()).thenReturn("synthetic");
    server.createContext(
        "/v1/rerank",
        exchange -> {
          byte[] response =
              json(Map.of(
                      "results",
                      List.of(
                          Map.of("index", 1, "relevance_score", 0.9),
                          Map.of("index", 0, "relevance_score", 0.5))))
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          try (var out = exchange.getResponseBody()) {
            out.write(response);
          }
        });
    var gateway = new ModelGateway(cfg, mapper);
    var result =
        gateway.rerank(
            "query",
            List.of(
                Map.of("body", "a", "evidence_id", "a"), Map.of("body", "b", "evidence_id", "b")),
            2);
    assertThat(result.get(0).get("evidence_id")).isEqualTo("b");
    assertThat(result.get(1).get("evidence_id")).isEqualTo("a");
  }
  @Test void oversizedChunkedBodyFailsBeforeServerFinishesResponse() throws Exception {
    var release=new java.util.concurrent.CountDownLatch(1);
    server.createContext("/large",exchange->{
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200,0);
      try(var body=exchange.getResponseBody()) {
        body.write(new byte[2_000_001]);body.flush();
        release.await(5,java.util.concurrent.TimeUnit.SECONDS);
      } catch(java.io.IOException ignored) {
        // Client is expected to cancel the response.
      } catch(InterruptedException ex) {Thread.currentThread().interrupt();}
    });
    var cfg=mock(PlatformSettings.class);
    when(cfg.modelMode()).thenReturn("api");
    when(cfg.modelApiKey()).thenReturn("synthetic");
    when(cfg.modelBaseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
    long start=System.nanoTime();
    try {
      assertThatThrownBy(()->new ModelGateway(cfg,mapper,1,10).request("/large",Map.of()))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(3));
    } finally {release.countDown();}
  }

  @Test void requestDeadlineAlsoBoundsBodyStallingAfterHeaders() throws Exception {
    var release=new java.util.concurrent.CountDownLatch(1);
    server.createContext("/stalled-body",exchange->{
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200,0);
      try(var body=exchange.getResponseBody()) {
        body.write('{');body.flush();
        release.await(4,java.util.concurrent.TimeUnit.SECONDS);
        body.write('}');
      } catch(java.io.IOException ignored) {
      } catch(InterruptedException ex) {Thread.currentThread().interrupt();}
    });
    var cfg=mock(PlatformSettings.class);
    when(cfg.modelMode()).thenReturn("api");
    when(cfg.modelApiKey()).thenReturn("synthetic");
    when(cfg.modelBaseUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
    long start=System.nanoTime();
    try {
      assertThatThrownBy(()->new ModelGateway(cfg,mapper,1,1).request("/stalled-body",Map.of()))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(3));
    } finally {release.countDown();}
  }

}
