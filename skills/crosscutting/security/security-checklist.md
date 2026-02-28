# Skill: Security Checklist (OWASP Top 10 for Spring Boot + React)

Every PR must satisfy these checks. The review-worker validates them automatically.

## A01: Broken Access Control

- [ ] Every endpoint has explicit `@PreAuthorize` or Spring Security config. No endpoint is accidentally public.
- [ ] Use method-level security (`@PreAuthorize("hasRole('ADMIN')")`) for sensitive operations.
- [ ] CORS is configured explicitly in `SecurityFilterChain`. Never use `allowedOrigins("*")` in non-dev profiles.
- [ ] React routes that require auth are wrapped in `<ProtectedRoute>` component.
- [ ] API responses never include data the requesting user is not authorized to see (row-level filtering in queries).

## A02: Cryptographic Failures

- [ ] No secrets in source code. All secrets come from environment variables or Azure Key Vault.
- [ ] Passwords hashed with bcrypt (Spring Security default). Never MD5/SHA1.
- [ ] TLS everywhere in production. HTTP allowed only on internal Docker network.
- [ ] PII encrypted at rest in the database for fields marked in `security-policy.yml`.

## A03: Injection

- [ ] Use parameterized queries exclusively. Spring Data JPA does this by default.
- [ ] For native SQL (`@Query(nativeQuery=true)`), ALWAYS use named parameters (`:param`), never string concatenation.
- [ ] React: never use `dangerouslySetInnerHTML`. If absolutely necessary, sanitize with DOMPurify.
- [ ] Validate all inputs with Bean Validation (`@Valid`, `@NotBlank`, `@Size`, `@Pattern`).

## A04: Insecure Design

- [ ] Rate limiting on authentication endpoints (Spring Security + bucket4j or similar).
- [ ] Business logic validates state transitions (e.g., order status cannot go from DELIVERED back to PENDING).
- [ ] File uploads: validate MIME type, enforce size limit, store outside webroot.

## A05: Security Misconfiguration

- [ ] Spring Actuator endpoints are not exposed publicly. Only `/health` and `/info` are permitted without auth.
- [ ] Stack traces are never returned in error responses (the `GlobalExceptionHandler` returns generic messages).
- [ ] Default credentials are never used. Docker images use non-root users.
- [ ] HTTP security headers set: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Strict-Transport-Security`.

## A06: Vulnerable and Outdated Components

- [ ] `mvn dependency-check:check` runs in CI with zero critical/high vulnerabilities.
- [ ] `npm audit --audit-level=high` runs in CI with zero high/critical.
- [ ] Dependency versions are pinned in `pom.xml` (via `dependencyManagement`) and `package-lock.json`.

## A07: Identification and Authentication Failures

- [ ] JWT validation uses Keycloak JWKS endpoint. Tokens are validated for signature, expiry, issuer, and audience.
- [ ] Session tokens (if used) are HttpOnly, Secure, SameSite=Strict.
- [ ] Failed login attempts are rate-limited and logged.

## A08: Software and Data Integrity Failures

- [ ] CI/CD pipelines verify checksums of downloaded dependencies.
- [ ] Docker images are pulled from trusted registries only (as per `security-policy.yml` network policy).
- [ ] Deserialization: use `@JsonIgnoreProperties(ignoreUnknown = true)` on DTOs. Never deserialize untrusted data into arbitrary types.

## A09: Security Logging and Monitoring Failures

- [ ] Authentication events (login, logout, failure) are logged at INFO level.
- [ ] Authorization failures are logged at WARN level with user ID and requested resource.
- [ ] PII is masked in logs (see `security-policy.yml` redaction rules).
- [ ] All logs include `traceId` for correlation (see otel-spring skill).

## A10: Server-Side Request Forgery (SSRF)

- [ ] No endpoint accepts a user-provided URL and fetches it server-side without validation.
- [ ] If URL fetching is required, maintain an allowlist of permitted domains.
- [ ] Internal service URLs (Docker DNS names) are never exposed to API consumers.

## Quick Spring Security Config Template

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())  // APIs are stateless with JWT
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers("/v1/**").authenticated()
            .anyRequest().denyAll()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwkSetUri("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}"))
        )
        .headers(h -> h
            .contentTypeOptions(Customizer.withDefaults())
            .frameOptions(f -> f.deny())
        )
        .build();
}
```
