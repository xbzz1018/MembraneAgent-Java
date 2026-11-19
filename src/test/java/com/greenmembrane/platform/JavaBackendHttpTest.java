package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Starts a real embedded Tomcat port; does not depend on MockMvc. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "app.storage=./target/http-objects",
      "app.demo=true",
      "app.execution=inline",
      "app.model-mode=rules",
      "app.vector-enabled=false",
      "app.redis-enabled=false"
    })
class JavaBackendHttpTest {
  @LocalServerPort int port;

  @Test
  void embeddedTomcatAcceptsLoginAndScopedChat() throws Exception {
    var client = HttpClient.newHttpClient();
    String base = "http://127.0.0.1:" + port;
    var health =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/actuator/health")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(read(health.body(), Map.class)).containsEntry("status", "UP");
    var login =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json(Map.of("username", "employee", "password", "local-demo-change-me"))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    String token = (String) read(login.body(), Map.class).get("access_token");
    var answer =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/chat"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json(Map.of("project_id", "demo-project", "message", "什么是膜结构"))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(answer.statusCode()).isEqualTo(200);
    assertThat(answer.body()).contains("direct_chat", "rules");
    var unauthorized =
        client.send(
            HttpRequest.newBuilder(URI.create(base + "/api/v1/projects")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(unauthorized.statusCode()).isEqualTo(401);
  }
}
