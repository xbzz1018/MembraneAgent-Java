package com.greenmembrane.platform;

import static com.greenmembrane.platform.BusinessSupport.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final BusinessRepository repo;
  private final PasswordEncoder passwords;
  private final JwtEncoder encoder;

  public AuthController(BusinessRepository repo, PasswordEncoder passwords, JwtEncoder encoder) {
    this.repo = repo;
    this.passwords = passwords;
    this.encoder = encoder;
  }

  public record Login(
      @NotBlank @Size(max = 128) String username, @NotBlank @Size(max = 256) String password) {}

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody Login request) {
    var user = repo.one("SELECT * FROM mb_users WHERE username=?", request.username());
    if (user == null || !passwords.matches(request.password(), text(user, "password_hash")))
      throw error(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    var principal =
        Map.of(
            "user_id",
            text(user, "id"),
            "username",
            request.username(),
            "tenant_id",
            text(user, "tenant_id"),
            "role",
            text(user, "role"));
    var claims =
        JwtClaimsSet.builder()
            .issuer("membrane-java")
            .subject(text(user, "id"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(7200))
            .claim("tenant_id", text(user, "tenant_id"))
            .claim("role", text(user, "role"))
            .build();
    String token =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return Map.of("access_token", token, "token_type", "bearer", "principal", principal);
  }

  @GetMapping("/me")
  public Identity me(@AuthenticationPrincipal Jwt jwt) {
    return Identity.from(jwt);
  }
}
