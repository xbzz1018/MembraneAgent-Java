package com.greenmembrane.platform;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(IngestAdmission.Full.class)
  ResponseEntity<?> backlog(IngestAdmission.Full ex) {
    return ResponseEntity.status(ex.getStatusCode()).header(HttpHeaders.RETRY_AFTER,"2")
        .body(Map.of("code",ex.code,"detail",ex.getReason()));
  }
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> business(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode())
        .body(Map.of("detail", ex.getReason() == null ? "请求失败" : ex.getReason()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> invalid() {
    return ResponseEntity.unprocessableEntity().body(Map.of("detail", "请求字段校验失败"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database() {
    return ResponseEntity.status(503).body(Map.of("detail", "数据库操作失败；检查服务端状态"));
  }
}
