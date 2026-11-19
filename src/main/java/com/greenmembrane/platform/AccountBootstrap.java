package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AccountBootstrap implements ApplicationRunner {
  private final PlatformSettings cfg;
  private final BusinessRepository repo;
  private final PasswordEncoder passwords;
  private final TransactionTemplate tx;
  private final String username, password, tenant;

  public AccountBootstrap(
      PlatformSettings cfg,
      BusinessRepository repo,
      PasswordEncoder passwords,
      TransactionTemplate tx,
      @Value("${BOOTSTRAP_ADMIN_USERNAME:}") String username,
      @Value("${BOOTSTRAP_ADMIN_PASSWORD:}") String password,
      @Value("${BOOTSTRAP_TENANT_ID:}") String tenant) {
    this.cfg = cfg;
    this.repo = repo;
    this.passwords = passwords;
    this.tx = tx;
    this.username = username;
    this.password = password;
    this.tenant = tenant;
  }

  public void run(ApplicationArguments args) {
    if (cfg.demo()) return;
    Integer count = repo.sql().queryForObject("SELECT COUNT(*) FROM mb_users", Integer.class);
    if (count != null && count > 0) return;
    if (username.isBlank()
        || username.length() > 128
        || password.length() < 16
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || tenant.isBlank()
        || tenant.length() > 64)
      throw new IllegalStateException(
          "First non-demo startup requires BOOTSTRAP_ADMIN_USERNAME, BOOTSTRAP_ADMIN_PASSWORD"
              + " (16-72 characters), BOOTSTRAP_TENANT_ID");
    tx.executeWithoutResult(
        s ->
            repo.sql()
                .update(
                    "INSERT INTO mb_users VALUES(?,?,?,?,?)",
                    id(),
                    username,
                    passwords.encode(password),
                    tenant,
                    "ADMIN"));
  }
}
