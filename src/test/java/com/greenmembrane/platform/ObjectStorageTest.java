package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

class ObjectStorageTest {
  HttpServer server;

  @Test
  void minioSdkStoresAndVerifiesContentAddressedObjects() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<byte[]> stored = new AtomicReference<>();
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestURI().getQuery() != null
              && exchange.getRequestURI().getQuery().contains("location")) {
            byte[] xml =
                "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"
                    .getBytes();
            exchange.sendResponseHeaders(200, xml.length);
            try (var out = exchange.getResponseBody()) {
              out.write(xml);
            }
            return;
          }
          String method = exchange.getRequestMethod();
          if ("PUT".equals(method)) {
            stored.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("ETag", "\"synthetic-etag\"");
            exchange.sendResponseHeaders(200, -1);
          } else if (stored.get() == null) {
            if ("HEAD".equals(method)) exchange.sendResponseHeaders(404, -1);
            else {
              byte[] error =
                  "<Error><Code>NoSuchKey</Code><Message>missing</Message></Error>".getBytes();
              exchange.sendResponseHeaders(404, error.length);
              try (var out = exchange.getResponseBody()) {
                out.write(error);
              }
            }
          } else if ("HEAD".equals(method)) {
            exchange
                .getResponseHeaders()
                .set("Content-Length", String.valueOf(stored.get().length));
            exchange.getResponseHeaders().set("ETag", "\"synthetic-etag\"");
            exchange.getResponseHeaders().set("Last-Modified", "Tue, 17 Nov 2026 06:00:00 GMT");
            exchange.sendResponseHeaders(200, -1);
          } else {
            exchange.sendResponseHeaders(200, stored.get().length);
            try (var out = exchange.getResponseBody()) {
              out.write(stored.get());
            }
          }
          exchange.close();
        });
    server.start();
    try {
      var cfg = mock(PlatformSettings.class);
      when(cfg.storage()).thenReturn("./target/unused");
      var storage =
          new ObjectStorage(
              cfg,
              "minio",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "synthetic-access",
              "synthetic-secret",
              "synthetic-bucket");
      byte[] body = "SYNTHETIC material document".getBytes();
      String hash = sha(body);
      storage.put(hash, body);
      assertThat(storage.get(hash)).isEqualTo(body);
      storage.put(hash, body);
      stored.set("tampered".getBytes());
      assertThatThrownBy(() -> storage.put(hash, body)).hasMessageContaining("存储失败");
    } finally {
      server.stop(0);
    }
  }
}
