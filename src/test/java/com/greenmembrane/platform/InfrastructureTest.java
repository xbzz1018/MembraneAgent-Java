package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.minio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "JAVA_INFRA_TESTS", matches = "true")
class InfrastructureTest {
  @Test
  void postgresPgvectorAndBusinessReviewUseRealTransactions() throws Exception {
    var source =
        new DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:55438/membrane_java",
            "java_check",
            "synthetic-java-check-only");
    Flyway.configure()
        .dataSource(source)
        .locations("classpath:db/migration", "classpath:db/postgres")
        .placeholders(Map.of("embeddingDimensions", "3"))
        .load()
        .migrate();
    var factoryBean = new org.mybatis.spring.SqlSessionFactoryBean();
    factoryBean.setDataSource(source);
    var conf = new org.apache.ibatis.session.Configuration();
    conf.addMapper(SqlMapper.class);
    conf.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
    factoryBean.setConfiguration(conf);
    var session = new org.mybatis.spring.SqlSessionTemplate(factoryBean.getObject());
    var jdbc = new SqlAccess(session.getMapper(SqlMapper.class));
    var repo = new BusinessRepository(jdbc);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    String project = id(), user = id();
    jdbc.update("INSERT INTO mb_projects VALUES(?,?,?)", project, "synthetic", "SYNTHETIC PG test");
    jdbc.update(
        "INSERT INTO mb_users VALUES(?,?,?,?,?)",
        user,
        user,
        "not-a-real-password-hash",
        "synthetic",
        "ADMIN");
    var who = new Identity(user, "synthetic", "ADMIN");
    var cfg = mock(PlatformSettings.class);
    when(cfg.demo()).thenReturn(true);
    when(cfg.storage()).thenReturn(Files.createTempDirectory("membrane-pg-").toString());
    when(cfg.vectorEnabled()).thenReturn(true);
    when(cfg.embeddingModel()).thenReturn("synthetic-3d");
    when(cfg.embeddingDimensions()).thenReturn(3);
    var model = mock(ModelGateway.class);
    when(model.allowsInternal()).thenReturn(true);
    when(model.normalize(anyString())).thenAnswer(a -> a.getArgument(0));
    when(model.embed(anyString())).thenReturn(new float[] {1, 0, 0});
    when(model.organize(anyString(), anyString())).thenReturn("");
    var objects = new ObjectStorage(cfg, "local", "", "", "", "");
    var docs = new DocumentService(repo, tx, objects);
    var retrieval = new RetrievalService(repo, model, cfg);
    var outbox = new OutboxService(repo,tx,cfg);
    var reviews = new ReviewService(repo, retrieval, tx, docs, model, outbox);
    var tasks = new TaskService(repo, tx, outbox);
    var processor =
        new JobProcessor(
            repo, tasks, docs, new DocumentParser(), reviews, model, cfg, tx, retrieval, mock(SpecificationCache.class));
    List<String> ids = new ArrayList<>();
    for (String type : List.of("design", "spec")) {
      String body =
          "SYNTHETIC MODEL-T1\n厚度：1.0 mm\n抗拉强度："
              + ("design".equals(type) ? "3000" : "4000")
              + " N/5cm";
      var doc =
          docs.upload(
              who,
              project,
              "R1",
              type,
              "MODEL-T1",
              "internal",
              new org.springframework.mock.web.MockMultipartFile(
                  "file", "synthetic.txt", "text/plain", body.getBytes(StandardCharsets.UTF_8)));
      ids.add(text(doc, "id"));
      var task = tasks.index(who, text(doc, "id"));
      processor.process(text(task, "id"), "pg-check/" + text(task, "id"), 1);
    }
    var found =
        retrieval.search(
            who,
            project,
            "MODEL-T1",
            new RetrievalService.Filters("MODEL-T1", "R1", "spec"),
            10,
            true);
    assertThat(found).hasSize(1);
    assertThat(found.get(0)).containsKey("rrf_score");
    var task = reviews.create(who, project, ids, "pg-review-" + id());
    processor.process(text(task, "id"), "pg-check/review", 1);
    var review = reviews.view(who, text(task, "target_id"));
    var issue = repo.required("SELECT * FROM mb_issues WHERE review_id=?", text(task, "target_id"));
    reviews.resolve(who, text(task, "target_id"), text(issue, "id"), 1, "synthetic checked");
    var receipt =
        reviews.decide(
            who,
            text(task, "target_id"),
            "pg-command-" + id(),
            2,
            text(review, "content_hash"),
            "approved",
            "synthetic approval");
    assertThat(receipt.get("status")).isEqualTo("approved");
  }

  @Test
  void springRedisTokenBucketIsAtomicAndEnforcesLimits() throws Exception {
    var connection = new LettuceConnectionFactory("127.0.0.1", 56388);
    connection.afterPropertiesSet();
    connection.start();
    try {
      var redis = new StringRedisTemplate(connection);
      var factory = new StaticListableBeanFactory();
      factory.addBean("redis", redis);
      var repo = mock(BusinessRepository.class);
      when(repo.one(anyString(), any()))
          .thenReturn(Map.of("tenant_id", "synthetic", "role", "EMPLOYEE"));
      var guard =
          new RequestGuard(repo, factory.getBeanProvider(StringRedisTemplate.class), true, 1);
      String user = id();
      Jwt jwt =
          Jwt.withTokenValue("synthetic")
              .header("alg", "HS256")
              .subject(user)
              .claim("tenant_id", "synthetic")
              .claim("role", "EMPLOYEE")
              .build();
      SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
      var request = new MockHttpServletRequest("POST", "/api/v1/chat");
      var first = new MockHttpServletResponse();
      guard.doFilter(request, first, new MockFilterChain());
      assertThat(first.getStatus()).isEqualTo(200);
      var second = new MockHttpServletResponse();
      guard.doFilter(
          new MockHttpServletRequest("POST", "/api/v1/chat"), second, new MockFilterChain());
      assertThat(second.getStatus()).isEqualTo(429);
      String key = "membrane:java:rate:" + sha("synthetic:" + user);
      assertThat(redis.getExpire(key)).isPositive();
    } finally {
      SecurityContextHolder.clearContext();
      connection.destroy();
    }
  }
}
