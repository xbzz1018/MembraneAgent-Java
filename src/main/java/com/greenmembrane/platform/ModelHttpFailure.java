package com.greenmembrane.platform;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The caller sees a gateway error; a worker needs the upstream retry decision. */
final class ModelHttpFailure extends ResponseStatusException {
  private final int upstreamStatus;

  ModelHttpFailure(int upstreamStatus) {
    super(HttpStatus.BAD_GATEWAY, "模型服务请求失败");
    this.upstreamStatus = upstreamStatus;
  }

  boolean retryable() {
    return switch (upstreamStatus) {
      case 408, 425, 429, 500, 502, 503, 504 -> true;
      default -> false;
    };
  }

  String taskError() {
    if (upstreamStatus == 401 || upstreamStatus == 403) return "model_credentials_rejected";
    return retryable() ? "model_temporarily_unavailable" : "model_request_rejected";
  }
}
