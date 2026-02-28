# Skill: Controller Patterns

## REST Controller Template

```java
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users")
public class UserController implements UsersApi {
    // UsersApi is generated from OpenAPI spec (contract-first)
    private final UserService userService;

    @Override
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody CreateUserRequest request) {
        var user = userService.create(request);
        var location = URI.create("/v1/users/" + user.id());
        return ResponseEntity.created(location).body(user);
    }

    @Override
    public ResponseEntity<PagedUsers> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = userService.list(PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    @Override
    public ResponseEntity<UserResponse> updateUser(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.update(id, request));
    }

    @Override
    public ResponseEntity<Void> deleteUser(@PathVariable UUID id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
```

## HTTP Status Codes

| Operation | Success | Common Errors |
|-----------|---------|---------------|
| GET (single) | 200 | 404 |
| GET (list) | 200 | - |
| POST (create) | 201 + Location header | 400, 409 |
| PUT (update) | 200 | 400, 404, 409 |
| DELETE | 204 (no body) | 404 |

## Error Handling

Controllers MUST NOT catch exceptions to format errors. The `GlobalExceptionHandler` does this. If you need a custom business error:

```java
// Define the exception
public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String email) {
        super("User with email already exists");  // no PII in message
    }
}

// Add handler in GlobalExceptionHandler
@ExceptionHandler(UserAlreadyExistsException.class)
public ResponseEntity<ErrorEnvelope> handleUserExists(UserAlreadyExistsException ex) {
    return ResponseEntity.status(409).body(
        ErrorEnvelope.of("CONFLICT", ex.getMessage(), MDC.get("traceId")));
}
```

## Pagination

Always use Spring's `Pageable` and return a wrapper DTO:

```java
public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages()
        );
    }
}
```

## Validation

Use Bean Validation annotations on request DTOs:

```java
public record CreateUserRequest(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Email String email,
    @NotNull UserType type
) {}
```

For custom validation, implement `ConstraintValidator`:

```java
@Target(FIELD) @Retention(RUNTIME)
@Constraint(validatedBy = CodiceFiscaleValidator.class)
public @interface ValidCodiceFiscale {
    String message() default "Invalid codice fiscale";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

## Rules for Workers

1. Controllers are thin. They delegate to `@Service` classes. No business logic in controllers.
2. Always implement the generated `Api` interface from `api-stubs`. This ensures the implementation matches the contract.
3. Always use `@Valid` on `@RequestBody` parameters.
4. Return `ResponseEntity` with explicit status codes. Do not rely on implicit 200.
5. POST (create) must return 201 with a `Location` header pointing to the new resource.
6. DELETE must return 204 with no body.
7. Never return JPA entities directly. Map to response DTOs.
