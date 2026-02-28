# Skill: Testing Strategy

## Test Pyramid

```
        /  Integration  \       ~10% — Full stack with Testcontainers
       /   Slice Tests   \      ~30% — @WebMvcTest, @DataJpaTest
      /    Unit Tests      \    ~60% — Plain JUnit + Mockito
```

**Target: 80% line coverage** (enforced by quality gates).

## Unit Tests (Mockito)

Test service logic in isolation. Mock all dependencies.

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepo;
    @Mock OutboxRepository outboxRepo;
    @Mock ObjectMapper objectMapper;
    @InjectMocks UserService userService;

    @Test
    void create_shouldSaveUserAndOutboxEntry() {
        var req = new CreateUserRequest("Alice", "alice@example.com", UserType.STANDARD);
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(false);
        when(userRepo.save(any())).thenAnswer(inv -> {
            var u = inv.getArgument(0, User.class);
            u.setId(UUID.randomUUID());
            return u;
        });
        when(objectMapper.valueToTree(any())).thenReturn(mock(JsonNode.class));

        var result = userService.create(req);

        assertThat(result.name()).isEqualTo("Alice");
        verify(userRepo).save(any(User.class));
        verify(outboxRepo).save(any(OutboxEntry.class));
    }

    @Test
    void create_shouldThrow_whenEmailExists() {
        var req = new CreateUserRequest("Alice", "alice@example.com", UserType.STANDARD);
        when(userRepo.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.create(req))
            .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepo, never()).save(any());
    }

    @Test
    void getById_shouldThrow_whenNotFound() {
        var id = UUID.randomUUID();
        when(userRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(id))
            .isInstanceOf(EntityNotFoundException.class);
    }
}
```

## Slice Test: @WebMvcTest (Controller)

Tests the web layer in isolation: serialization, validation, error handling.

```java
@WebMvcTest(UserController.class)
@Import(GlobalExceptionHandler.class)
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean UserService userService;

    @Test
    void createUser_shouldReturn201() throws Exception {
        var response = new UserResponse(UUID.randomUUID(), "Alice",
            "alice@example.com", UserType.STANDARD, Instant.now());
        when(userService.create(any())).thenReturn(response);

        mockMvc.perform(post("/v1/users")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"name":"Alice","email":"alice@example.com","type":"STANDARD"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void createUser_shouldReturn400_whenNameBlank() throws Exception {
        mockMvc.perform(post("/v1/users")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"name":"","email":"alice@example.com","type":"STANDARD"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details[0].field").value("name"));
    }

    @Test
    void getUser_shouldReturn404_whenNotFound() throws Exception {
        when(userService.getById(any())).thenThrow(
            new EntityNotFoundException("User not found"));

        mockMvc.perform(get("/v1/users/" + UUID.randomUUID()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
```

## Slice Test: @DataJpaTest (Repository)

Tests JPA queries with an embedded or Testcontainers database.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class UserRepositoryTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", pg::getJdbcUrl);
        reg.add("spring.datasource.username", pg::getUsername);
        reg.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired UserRepository userRepo;

    @Test
    void findByEmail_shouldReturnUser() {
        var user = new User();
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.setType(UserType.STANDARD);
        userRepo.save(user);

        var found = userRepo.findByEmail("alice@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Alice");
    }

    @Test
    void findByEmail_shouldReturnEmpty_whenNotExists() {
        assertThat(userRepo.findByEmail("nobody@example.com")).isEmpty();
    }
}
```

## Integration Test (Full Stack)

Tests the full request lifecycle: HTTP -> Controller -> Service -> Repository -> DB.

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class UserIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", pg::getJdbcUrl);
        reg.add("spring.datasource.username", pg::getUsername);
        reg.add("spring.datasource.password", pg::getPassword);
    }

    @Autowired TestRestTemplate rest;

    @Test
    void fullCrudLifecycle() {
        // Create
        var createReq = Map.of("name", "Alice", "email", "alice@test.com", "type", "STANDARD");
        var createResp = rest.postForEntity("/v1/users", createReq, Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var id = createResp.getBody().get("id").toString();

        // Read
        var getResp = rest.getForEntity("/v1/users/" + id, Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("name")).isEqualTo("Alice");

        // Delete
        rest.delete("/v1/users/" + id);
        var afterDelete = rest.getForEntity("/v1/users/" + id, Map.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
```

## Coverage Configuration (JaCoCo)

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals><goal>report</goal></goals>
        </execution>
        <execution>
            <id>check</id>
            <phase>verify</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <rules>
                    <rule>
                        <element>BUNDLE</element>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Rules for Workers

1. Every public service method needs at least one unit test (happy path + error path).
2. Every controller endpoint needs a `@WebMvcTest` for: success, validation error, not-found.
3. Custom repository queries need `@DataJpaTest` verification.
4. Integration tests cover the critical user journeys (CRUD lifecycle), not every edge case.
5. Use AssertJ (`assertThat`) for assertions, not JUnit's `assertEquals`.
6. Test class naming: `{ClassName}Test` for unit/slice, `{Feature}IntegrationTest` for integration.
7. Never use `@SpringBootTest` when `@WebMvcTest` or `@DataJpaTest` suffices. Slice tests are faster.
8. Testcontainers PostgreSQL must match the production version (16-alpine).
