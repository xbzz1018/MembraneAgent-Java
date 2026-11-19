package com.greenmembrane.platform;

import org.springframework.security.oauth2.jwt.Jwt;

public record Identity(String userId, String tenantId, String role) {
  public static Identity from(Jwt jwt) {
    return new Identity(
        jwt.getSubject(), jwt.getClaimAsString("tenant_id"), jwt.getClaimAsString("role"));
  }

  public boolean admin() {
    return "ADMIN".equals(role);
  }
}
