package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class AccountController {
  private final BusinessRepository repo;
  private final PasswordEncoder passwords;

  public AccountController(BusinessRepository repo, PasswordEncoder passwords) {
    this.repo = repo;
    this.passwords = passwords;
  }

  public record Create(
      @NotBlank @Size(max = 128) String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotNull @Pattern(regexp = "EMPLOYEE|BUSINESS|DESIGNER|REVIEWER") String role) {}

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create request) {
    var who = Identity.from(jwt);
    repo.role(who);
    if (request.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "密码超过72字节");
    String id = id();
    try {
      repo.sql()
          .update(
              "INSERT INTO mb_users VALUES(?,?,?,?,?)",
              id,
              request.username(),
              passwords.encode(request.password()),
              who.tenantId(),
              request.role());
    } catch (DuplicateKeyException ex) {
      throw error(HttpStatus.CONFLICT, "用户名已存在");
    }
    return Map.of("id", id, "username", request.username(), "role", request.role());
  }

  @GetMapping
  public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
    var who = Identity.from(jwt);
    repo.role(who);
    return repo.sql()
        .queryForList(
            "SELECT id,username,role FROM mb_users WHERE tenant_id=? ORDER BY username",
            who.tenantId());
  }
}
