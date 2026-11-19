package com.greenmembrane.platform;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DemoData implements ApplicationRunner {
  private final PlatformSettings cfg;
  private final BusinessRepository repo;
  private final PasswordEncoder passwords;

  public DemoData(PlatformSettings cfg, BusinessRepository repo, PasswordEncoder passwords) {
    this.cfg = cfg;
    this.repo = repo;
    this.passwords = passwords;
  }

  public void run(ApplicationArguments args) {
    if (!cfg.demo()) return;
    if (cfg.demoPassword().length() < 12)
      throw new IllegalStateException("Demo password must have at least 12 characters");
    for (String role : new String[] {"ADMIN", "EMPLOYEE", "BUSINESS", "DESIGNER", "REVIEWER"}) {
      String user = role.toLowerCase();
      if (repo.one("SELECT id FROM mb_users WHERE username=?", user) == null)
        repo.sql()
            .update(
                "INSERT INTO mb_users VALUES(?,?,?,?,?)",
                user,
                user,
                passwords.encode(cfg.demoPassword()),
                "demo-company",
                role);
    }
    if (repo.one("SELECT id FROM mb_projects WHERE id='demo-project'") == null) {
      repo.sql()
          .update(
              "INSERT INTO mb_projects VALUES('demo-project','demo-company','SYNTHETIC 停车棚资料核对')");
      for (String user : new String[] {"admin", "employee", "business", "designer", "reviewer"})
        repo.sql().update("INSERT INTO mb_members VALUES('demo-project',?)", user);
    }
  }
}
