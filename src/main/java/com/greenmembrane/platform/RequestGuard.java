package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {
  private final BusinessRepository repo;
  private final ObjectProvider<StringRedisTemplate> redis;
  private final boolean enabled;
  private final int capacity;
  static final String LUA =
      """
      local values = redis.call('HMGET', KEYS[1], 'tokens', 'updated_ms')
      local tokens = tonumber(values[1]) or tonumber(ARGV[1])
      local updated = tonumber(values[2]) or tonumber(ARGV[3])
      local elapsed = math.max(0, tonumber(ARGV[3]) - updated)
      tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]))
      local allowed = 0
      if tokens >= 1 then tokens = tokens - 1; allowed = 1 end
      redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_ms', ARGV[3])
      redis.call('PEXPIRE', KEYS[1], 120000)
      return allowed
      """;

  public RequestGuard(
      BusinessRepository repo,
      ObjectProvider<StringRedisTemplate> redis,
      @Value("${app.redis-enabled:false}") boolean enabled,
      @Value("${app.rate-capacity:60}") int capacity) {
    this.repo = repo;
    this.redis = redis;
    this.enabled = enabled;
    this.capacity = capacity;
    if (capacity < 1) throw new IllegalArgumentException("Rate capacity must be positive");
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication()
        instanceof JwtAuthenticationToken token) {
      var who = Identity.from(token.getToken());
      var user = repo.one("SELECT tenant_id,role FROM mb_users WHERE id=?", who.userId());
      if (user == null
          || !who.tenantId().equals(text(user, "tenant_id"))
          || !who.role().equals(text(user, "role"))) {
        reject(response, 401, "账号或角色已变化，请重新登录");
        return;
      }
      if (enabled) {
        try {
          String key = "membrane:java:rate:" + sha(who.tenantId() + ":" + who.userId());
          Long allowed =
              redis
                  .getObject()
                  .execute(
                      new DefaultRedisScript<>(LUA, Long.class),
                      List.of(key),
                      String.valueOf(capacity),
                      String.valueOf(capacity / 60000.0),
                      String.valueOf(System.currentTimeMillis()));
          if (allowed == null || allowed != 1) {
            response.setHeader("Retry-After", "1");
            reject(response, 429, "请求过于频繁");
            return;
          }
        } catch (RuntimeException ex) {
          reject(response, 503, "接口限流服务不可用");
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }

  private void reject(HttpServletResponse response, int status, String detail) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(json(java.util.Map.of("detail", detail)));
  }
}
