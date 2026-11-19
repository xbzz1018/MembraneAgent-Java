package com.greenmembrane.platform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class BusinessSupport {
  private static final ObjectMapper JSON = new ObjectMapper();

  private BusinessSupport() {}

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String now() {
    return Instant.now().toString();
  }

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String sha(String s) {
    return sha(s.getBytes(StandardCharsets.UTF_8));
  }

  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return JSON.readValue(value, type);
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted JSON", ex);
    }
  }

  public static List<Map<String, Object>> list(String value) {
    try {
      return JSON.readValue(value, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted JSON", ex);
    }
  }

  public static List<String> strings(String value) {
    try {
      return JSON.readValue(value, new TypeReference<>() {});
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted JSON", ex);
    }
  }

  public static String text(Map<String, Object> row, String key) {
    return Objects.toString(row.get(key), "");
  }

  public static ResponseStatusException error(HttpStatus status, String detail) {
    return new ResponseStatusException(status, detail);
  }
}
