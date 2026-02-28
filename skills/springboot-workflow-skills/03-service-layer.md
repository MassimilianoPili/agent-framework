# Skill: Service Layer Patterns

## Service Template

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // Default: read-only for queries
public class UserService {
    private final UserRepository userRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public UserResponse getById(UUID id) {
        return userRepo.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
    }

    public PagedResponse<UserResponse> list(Pageable pageable) {
        return PagedResponse.from(
            userRepo.findAll(pageable).map(this::toResponse)
        );
    }

    @Transactional  // Write operation: overrides class-level readOnly
    public UserResponse create(CreateUserRequest req) {
        if (userRepo.existsByEmail(req.email())) {
            throw new UserAlreadyExistsException(req.email());
        }
        var user = new User();
        user.setName(req.name());
        user.setEmail(req.email());
        user.setType(req.type());
        userRepo.save(user);

        // Outbox: publish event in same transaction
        outboxRepo.save(OutboxEntry.builder()
            .aggregateType("User")
            .aggregateId(user.getId().toString())
            .eventType("UserCreated")
            .payload(objectMapper.valueToTree(user))
            .build());

        return toResponse(user);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest req) {
        var user = userRepo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
        user.setName(req.name());
        // JPA dirty checking persists changes at transaction commit
        return toResponse(user);
    }

    @Transactional
    public void delete(UUID id) {
        if (!userRepo.existsById(id)) {
            throw new EntityNotFoundException("User not found: " + id);
        }
        userRepo.deleteById(id);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(),
            user.getEmail(), user.getType(), user.getCreatedAt());
    }
}
```

## Transactional Boundaries

| Rule | Implementation |
|------|---------------|
| Class-level `@Transactional(readOnly = true)` | All methods are read-only by default |
| Method-level `@Transactional` for writes | Overrides class default on create/update/delete |
| Outbox writes in same transaction | Never publish to Service Bus directly in a `@Transactional` method |
| No transaction spanning HTTP calls | If the service calls an external API, do it OUTSIDE the transaction |

## Domain Logic Encapsulation

Keep business rules in the service layer, not in controllers or repositories.

```java
// CORRECT: Business rule in service
@Transactional
public OrderResponse placeOrder(PlaceOrderRequest req) {
    var user = userRepo.findById(req.userId())
        .orElseThrow(() -> new EntityNotFoundException("User not found"));
    if (user.isSuspended()) {
        throw new BusinessException("SUSPENDED_USER", "Suspended users cannot place orders");
    }
    // ... create order
}

// WRONG: Business rule in controller
@PostMapping
public ResponseEntity<?> placeOrder(@RequestBody PlaceOrderRequest req) {
    var user = userService.getById(req.userId());
    if (user.isSuspended()) {  // This belongs in the service
        return ResponseEntity.status(403).body(...);
    }
}
```

## Mapping: Entity to DTO

Use private methods for simple mappings. Use MapStruct for complex cases.

```java
// Simple: private method (preferred for most cases)
private UserResponse toResponse(User user) {
    return new UserResponse(user.getId(), user.getName(), ...);
}

// Complex: MapStruct interface (when mappings have many fields or nested objects)
@Mapper(componentModel = "spring")
public interface OrderMapper {
    OrderResponse toResponse(Order order);
    @Mapping(target = "id", ignore = true)
    Order fromRequest(CreateOrderRequest request);
}
```

## Exception Strategy

| Exception | HTTP Status | When |
|-----------|-------------|------|
| `EntityNotFoundException` | 404 | Entity lookup returns empty |
| `BusinessException(code, msg)` | 409 or 422 | Business rule violation |
| `UserAlreadyExistsException` | 409 | Unique constraint (checked before save) |
| `OptimisticLockingFailureException` | 409 | Concurrent update (JPA `@Version`) |

```java
// Generic business exception
public class BusinessException extends RuntimeException {
    private final String code;
    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String getCode() { return code; }
}
```

## Rules for Workers

1. Services are the ONLY place for business logic. Controllers delegate; repositories query.
2. Always use constructor injection (`@RequiredArgsConstructor`). Never use `@Autowired` on fields.
3. Default to `@Transactional(readOnly = true)` at class level. Override per method for writes.
4. Never return JPA entities from public service methods. Always map to DTOs.
5. Check for existence before delete/update and throw `EntityNotFoundException`. Do not let JPA exceptions bubble up.
6. For create operations that publish events, always use the outbox pattern (write event in same transaction).
