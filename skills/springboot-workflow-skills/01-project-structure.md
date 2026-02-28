# Skill: Spring Boot Project Structure

## Maven Multi-Module Layout

```
backend/
├── pom.xml                          # Parent POM (dependencyManagement, plugins)
├── api-stubs/
│   └── pom.xml                      # OpenAPI codegen output (interfaces + DTOs)
├── app/
│   ├── pom.xml                      # Main application module
│   └── src/main/java/com/example/
│       ├── Application.java         # @SpringBootApplication
│       ├── config/                   # Spring @Configuration classes
│       │   ├── SecurityConfig.java
│       │   ├── JacksonConfig.java
│       │   └── ServiceBusConfig.java
│       ├── common/                   # Shared utilities
│       │   ├── error/
│       │   │   ├── ErrorEnvelope.java
│       │   │   └── GlobalExceptionHandler.java
│       │   └── messaging/
│       │       ├── OutboxEntry.java
│       │       └── IdempotentProcessor.java
│       ├── user/                     # Feature package: User
│       │   ├── UserController.java
│       │   ├── UserService.java
│       │   ├── UserRepository.java
│       │   ├── User.java            # JPA entity
│       │   └── dto/
│       │       ├── CreateUserRequest.java
│       │       └── UserResponse.java
│       └── order/                    # Feature package: Order
│           ├── OrderController.java
│           ├── OrderService.java
│           └── ...
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── db/migration/                # Flyway migrations
│       ├── V1__create_user_table.sql
│       └── V2__create_outbox_table.sql
└── src/test/java/com/example/
    ├── user/
    │   ├── UserControllerTest.java   # @WebMvcTest
    │   ├── UserServiceTest.java      # Unit test (Mockito)
    │   └── UserRepositoryTest.java   # @DataJpaTest
    └── integration/
        └── UserIntegrationTest.java  # @SpringBootTest + Testcontainers
```

## Feature-Package Organization

Group code by **business domain**, not by technical layer. Each feature package is a vertical slice.

```
# CORRECT: Feature packages
com.example.user.UserController
com.example.user.UserService
com.example.user.UserRepository

# WRONG: Layer packages
com.example.controller.UserController
com.example.service.UserService
com.example.repository.UserRepository
```

**Why**: Feature packages have high cohesion. When working on the User feature, all related classes are in one place. Layer packages scatter related code across the tree.

## Parent POM Essentials

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.x</version>
</parent>

<properties>
    <java.version>21</java.version>
    <openapi-generator.version>7.x</openapi-generator.version>
</properties>

<dependencyManagement>
    <!-- Pin versions for all modules here -->
</dependencyManagement>

<modules>
    <module>api-stubs</module>
    <module>app</module>
</modules>
```

## Key Dependencies

| Purpose | Dependency |
|---------|-----------|
| Web | `spring-boot-starter-web` |
| JPA | `spring-boot-starter-data-jpa` |
| Validation | `spring-boot-starter-validation` |
| Security | `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` |
| Observability | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |
| Database | `postgresql` (runtime) |
| Migration | `flyway-core` + `flyway-database-postgresql` |
| Test | `spring-boot-starter-test` + `testcontainers` |

## Rules for Workers

1. Never create classes in the root package (`com.example`). Everything belongs in a feature package or `common/` or `config/`.
2. One entity per file. No inner classes for entities.
3. DTOs are records. Entities are classes (JPA requires mutable objects).
4. Flyway migrations are numbered sequentially (`V1__`, `V2__`, ...). Never modify a migration that has been applied.
5. The `api-stubs` module is generated code. NEVER edit files in it. They are overwritten on every build.
