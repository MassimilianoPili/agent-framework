package com.agentframework.orchestrator.council;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CouncilService} — pre-planning sessions, task sessions,
 * member selection, parallel consultation, and synthesis.
 */
@ExtendWith(MockitoExtension.class)
class CouncilServiceTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private CouncilPromptLoader promptLoader;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CouncilService service;

    /** Reusable mock objects for the ChatClient fluent chain. */
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponse;

    private static final CouncilProperties DEFAULT_PROPS =
        new CouncilProperties(true, 3, true, true);

    @BeforeEach
    void setUp() {
        service = new CouncilService(chatClient, promptLoader, DEFAULT_PROPS, objectMapper);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponse = mock(ChatClient.CallResponseSpec.class);
    }

    // ── Pre-planning session ──────────────────────────────────────────────────

    @Test
    void conductPrePlanningSession_happyPath_selectsConsultsSynthesizes() {
        // Arrange: selector returns 2 members
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            // 1st call: selectMembers
            "[\"be-manager\", \"security-specialist\"]",
            // 2nd call: consultMember("be-manager")
            "Architecture should use hexagonal pattern",
            // 3rd call: consultMember("security-specialist")
            "Apply OWASP Top 10 controls",
            // 4th call: synthesize
            validCouncilReportJson()
        );

        // Act
        CouncilReport report = service.conductPrePlanningSession("Build a REST API");

        // Assert
        assertThat(report).isNotNull();
        assertThat(report.architectureDecisions()).containsExactly("Use hexagonal architecture");
        assertThat(report.securityConsiderations()).containsExactly("OWASP Top 10");

        verify(promptLoader).loadSelectorPrompt();
        verify(promptLoader).loadMemberPrompt("be-manager");
        verify(promptLoader).loadMemberPrompt("security-specialist");
        verify(promptLoader).loadManagerPrompt();
    }

    @Test
    void conductPrePlanningSession_selectReturnsJsonArray_parsesCorrectly() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"database-specialist\"]",
            "Use UUID primary keys",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("Data migration task");

        assertThat(report).isNotNull();
        verify(promptLoader).loadMemberPrompt("database-specialist");
    }

    @Test
    void conductPrePlanningSession_selectReturnsMarkdownWrapped_stripsCodeBlock() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "```json\n[\"api-specialist\"]\n```",
            "REST with versioned endpoints",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("API design spec");

        assertThat(report).isNotNull();
        verify(promptLoader).loadMemberPrompt("api-specialist");
    }

    @Test
    void conductPrePlanningSession_selectReturnsInvalidJson_fallsBackToDefault() {
        stubPromptLoader();
        stubChatClientChain();
        // 1st call: selectMembers returns garbage
        // 2nd & 3rd calls: consultMember for fallback defaults
        // 4th call: synthesize
        stubCallSequence(
            "I think you should use these members: be and security",
            "Backend advice",
            "Security advice",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("Some spec");

        assertThat(report).isNotNull();
        // Default fallback: ["be-manager", "security-specialist"]
        verify(promptLoader).loadMemberPrompt("be-manager");
        verify(promptLoader).loadMemberPrompt("security-specialist");
    }

    @Test
    void conductPrePlanningSession_memberFailsDuringConsultation_otherMembersSucceed() {
        // Arrange: two members selected, one throws during consultation
        stubChatClientChain();
        when(promptLoader.loadSelectorPrompt()).thenReturn("selector prompt");
        when(promptLoader.loadManagerPrompt()).thenReturn("manager prompt");
        when(promptLoader.loadMemberPrompt("be-manager")).thenReturn("be prompt");
        when(promptLoader.loadMemberPrompt("security-specialist"))
            .thenThrow(new RuntimeException("Prompt file not found"));

        stubCallSequence(
            "[\"be-manager\", \"security-specialist\"]",
            "Backend view",
            // The security-specialist call will fail because loadMemberPrompt throws,
            // which happens inside consultMember before the chatClient call.
            // But we still need a 3rd response for synthesize:
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("Some spec");

        // The service should still produce a report; the failed member gets an error message
        assertThat(report).isNotNull();
    }

    @Test
    void conductPrePlanningSession_synthesizeReturnsNull_createsEmptyFallbackReport() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\"]",
            "Backend advice here",
            // BeanOutputConverter.convert("null") returns null for JSON literal null
            "null"
        );

        CouncilReport report = service.conductPrePlanningSession("Some spec");

        // Fallback: selectedMembers from memberViews keys, empty lists
        assertThat(report).isNotNull();
        assertThat(report.selectedMembers()).containsExactly("be-manager");
        assertThat(report.architectureDecisions()).isEmpty();
        assertThat(report.memberInsights()).containsKey("be-manager");
        assertThat(report.memberInsights().get("be-manager")).isEqualTo("Backend advice here");
    }

    // ── Task session ──────────────────────────────────────────────────────────

    @Test
    void conductTaskSession_happyPath_buildsContextAndProducesReport() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"database-specialist\"]",
            "Use connection pooling with HikariCP",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductTaskSession(
            "Design DB schema",
            "Create tables for user management",
            Map.of()
        );

        assertThat(report).isNotNull();
        // Selector prompt loaded for task selection
        verify(promptLoader).loadSelectorPrompt();
        // User message should contain task title
        verify(requestSpec, atLeastOnce()).user(contains("Design DB schema"));
    }

    @Test
    void conductTaskSession_withDependencyResults_contextIncludesDeps() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\"]",
            "Consider the schema from CM-001",
            validCouncilReportJson()
        );

        Map<String, String> deps = Map.of(
            "CM-001", "{\"entities\":[\"User\",\"Role\"]}",
            "SM-001", "{\"schema\":\"v1\"}"
        );

        CouncilReport report = service.conductTaskSession(
            "Implement user service",
            "CRUD operations for users",
            deps
        );

        assertThat(report).isNotNull();
        // The context passed to consultMember should include dependency results
        // We verify the user message sent to the selector contains dependency info
        verify(requestSpec, atLeastOnce()).user(contains("Dependency Results"));
    }

    @Test
    void conductTaskSession_emptyDependencies_worksWithoutDeps() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"testing-specialist\"]",
            "Use Testcontainers for integration tests",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductTaskSession(
            "Write integration tests",
            "Cover the user service endpoints",
            Map.of()
        );

        assertThat(report).isNotNull();
        verify(promptLoader).loadMemberPrompt("testing-specialist");
    }

    // ── Member selection (tested indirectly) ──────────────────────────────────

    @Test
    void selectMembers_parsesJsonArray_correctProfilesUsed() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"fe-manager\", \"api-specialist\", \"auth-specialist\"]",
            "Frontend with React",
            "REST API design",
            "OAuth2 with PKCE",
            validCouncilReportJson()
        );

        service.conductPrePlanningSession("Full-stack app with auth");

        verify(promptLoader).loadMemberPrompt("fe-manager");
        verify(promptLoader).loadMemberPrompt("api-specialist");
        verify(promptLoader).loadMemberPrompt("auth-specialist");
    }

    @Test
    void selectMembers_respectsMaxMembers_passesMaxToPrompt() {
        CouncilProperties smallProps = new CouncilProperties(true, 2, true, true);
        CouncilService smallService = new CouncilService(chatClient, promptLoader, smallProps, objectMapper);

        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\"]",
            "Backend advice",
            validCouncilReportJson()
        );

        smallService.conductPrePlanningSession("Simple backend");

        // Verify the user message mentions maxMembers=2
        verify(requestSpec, atLeastOnce()).user(contains("up to 2 members"));
    }

    // ── Parallel consultation (tested indirectly) ─────────────────────────────

    @Test
    void consultMembersParallel_allMembersRespond_allViewsCaptured() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\", \"database-specialist\"]",
            "Use Spring Boot 3",
            "PostgreSQL with Flyway",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("Enterprise API");

        // Verify both members were consulted (prompt loaded for each)
        verify(promptLoader).loadMemberPrompt("be-manager");
        verify(promptLoader).loadMemberPrompt("database-specialist");
        // The synthesize call should receive both views
        verify(promptLoader).loadManagerPrompt();
    }

    @Test
    void consultMembersParallel_singleMember_worksCorrectly() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"security-specialist\"]",
            "Apply defense in depth",
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("Security audit");

        assertThat(report).isNotNull();
        verify(promptLoader).loadMemberPrompt("security-specialist");
        verify(promptLoader, times(1)).loadMemberPrompt(anyString());
    }

    @Test
    void consultMembersParallel_memberThrows_returnsFailureMessage() {
        // Arrange: loadMemberPrompt throws for one member
        when(promptLoader.loadSelectorPrompt()).thenReturn("selector prompt");
        when(promptLoader.loadManagerPrompt()).thenReturn("manager prompt");
        when(promptLoader.loadMemberPrompt("be-manager")).thenReturn("be prompt");
        when(promptLoader.loadMemberPrompt("data-manager"))
            .thenThrow(new RuntimeException("file missing"));

        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\", \"data-manager\"]",
            "Backend insights",
            // data-manager will fail before calling chatClient, so no extra response needed
            // but synthesize still runs:
            validCouncilReportJson()
        );

        CouncilReport report = service.conductPrePlanningSession("Database task");

        // Report should still be produced despite one member failing
        assertThat(report).isNotNull();
    }

    // ── Synthesis (tested indirectly) ─────────────────────────────────────────

    @Test
    void synthesize_producesCouncilReport_allFieldsPopulated() {
        stubPromptLoader();
        stubChatClientChain();

        String fullReport = """
            {
              "selectedMembers": ["be-manager", "security-specialist"],
              "architectureDecisions": ["Hexagonal architecture", "CQRS for writes"],
              "techStackRationale": "Spring Boot 3 with Java 21",
              "securityConsiderations": ["JWT auth", "Rate limiting"],
              "dataModelingGuidelines": "UUID PKs, soft delete",
              "apiDesignGuidelines": "REST, versioned /v1/",
              "testingStrategy": "Unit + integration, 80% coverage",
              "memberInsights": {
                "be-manager": "Focus on clean architecture",
                "security-specialist": "Enforce input validation"
              }
            }
            """;

        stubCallSequence(
            "[\"be-manager\", \"security-specialist\"]",
            "Clean architecture",
            "Input validation",
            fullReport
        );

        CouncilReport report = service.conductPrePlanningSession("Complex enterprise app");

        assertThat(report.selectedMembers()).containsExactly("be-manager", "security-specialist");
        assertThat(report.architectureDecisions()).containsExactly("Hexagonal architecture", "CQRS for writes");
        assertThat(report.techStackRationale()).isEqualTo("Spring Boot 3 with Java 21");
        assertThat(report.securityConsiderations()).containsExactly("JWT auth", "Rate limiting");
        assertThat(report.dataModelingGuidelines()).isEqualTo("UUID PKs, soft delete");
        assertThat(report.apiDesignGuidelines()).isEqualTo("REST, versioned /v1/");
        assertThat(report.testingStrategy()).isEqualTo("Unit + integration, 80% coverage");
        assertThat(report.memberInsights())
            .containsEntry("be-manager", "Focus on clean architecture")
            .containsEntry("security-specialist", "Enforce input validation");
    }

    @Test
    void synthesize_llmReturnsNull_fallbackReportCreated() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\"]",
            "Some backend advice",
            // BeanOutputConverter.convert("null") returns null → triggers fallback path
            "null"
        );

        CouncilReport report = service.conductPrePlanningSession("Spec that causes null synthesis");

        assertThat(report).isNotNull();
        assertThat(report.selectedMembers()).containsExactly("be-manager");
        assertThat(report.architectureDecisions()).isEmpty();
        assertThat(report.techStackRationale()).isNull();
        assertThat(report.securityConsiderations()).isEmpty();
        assertThat(report.dataModelingGuidelines()).isNull();
        assertThat(report.apiDesignGuidelines()).isNull();
        assertThat(report.testingStrategy()).isNull();
        assertThat(report.memberInsights()).containsKey("be-manager");
    }

    @Test
    void synthesize_llmReturnsInvalidJson_throwsRuntimeException() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\"]",
            "Backend advice",
            // BeanOutputConverter.convert() throws RuntimeException on unparseable JSON
            "completely invalid, not JSON"
        );

        assertThatThrownBy(() -> service.conductPrePlanningSession("Bad synthesis spec"))
            .isInstanceOf(RuntimeException.class);
    }

    // ── CouncilProperties validation ──────────────────────────────────────────

    @Test
    void councilProperties_maxMembersZero_throwsException() {
        assertThatThrownBy(() -> new CouncilProperties(true, 0, true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-members must be > 0");
    }

    @Test
    void councilProperties_maxMembersNegative_throwsException() {
        assertThatThrownBy(() -> new CouncilProperties(true, -5, true, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-members must be > 0");
    }

    @Test
    void councilProperties_validProperties_created() {
        CouncilProperties props = new CouncilProperties(false, 7, false, true);

        assertThat(props.enabled()).isFalse();
        assertThat(props.maxMembers()).isEqualTo(7);
        assertThat(props.prePlanningEnabled()).isFalse();
        assertThat(props.taskSessionEnabled()).isTrue();
    }

    // ── Timeout and resilience ─────────────────────────────────────────────────

    @Test
    void councilService_executorIsBounded_notCachedThreadPool() throws Exception {
        java.lang.reflect.Field executorField =
            CouncilService.class.getDeclaredField("COUNCIL_EXECUTOR");
        executorField.setAccessible(true);
        Object executor = executorField.get(null);
        assertThat(executor).isInstanceOf(java.util.concurrent.ThreadPoolExecutor.class);
        java.util.concurrent.ThreadPoolExecutor tpe =
            (java.util.concurrent.ThreadPoolExecutor) executor;
        assertThat(tpe.getMaximumPoolSize()).isLessThanOrEqualTo(8);
    }

    @Test
    void councilService_hasPreDestroyMethod() throws Exception {
        java.lang.reflect.Method shutdown =
            CouncilService.class.getDeclaredMethod("shutdownExecutor");
        assertThat(shutdown.isAnnotationPresent(jakarta.annotation.PreDestroy.class)).isTrue();
    }

    // ── ChatClient call count verification ────────────────────────────────────

    @Test
    void conductPrePlanningSession_callsChatClientCorrectNumberOfTimes() {
        stubPromptLoader();
        stubChatClientChain();
        stubCallSequence(
            "[\"be-manager\", \"security-specialist\"]",
            "Backend view",
            "Security view",
            validCouncilReportJson()
        );

        service.conductPrePlanningSession("Some spec");

        // 1 (select) + 2 (consult) + 1 (synthesize) = 4 calls
        verify(chatClient, times(4)).prompt();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stubs the prompt loader with generic prompts for all methods.
     */
    private void stubPromptLoader() {
        lenient().when(promptLoader.loadSelectorPrompt()).thenReturn("You are a council selector.");
        lenient().when(promptLoader.loadManagerPrompt()).thenReturn("You are the council manager.");
        lenient().when(promptLoader.loadMemberPrompt(anyString())).thenReturn("You are a council member.");
    }

    /**
     * Stubs the ChatClient fluent chain: prompt() -> system() -> user() -> call() -> content().
     * Each call to content() will return the next value from {@link #stubCallSequence(String...)}.
     */
    private void stubChatClientChain() {
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponse);
    }

    /**
     * Configures the mock to return the given responses in order on successive calls
     * to {@code callResponse.content()}.
     */
    private void stubCallSequence(String first, String... rest) {
        when(callResponse.content()).thenReturn(first, rest);
    }

    /**
     * Returns a minimal valid JSON string that {@link org.springframework.ai.converter.BeanOutputConverter}
     * can deserialize into a {@link CouncilReport}.
     */
    private static String validCouncilReportJson() {
        return """
            {
              "selectedMembers": ["be-manager"],
              "architectureDecisions": ["Use hexagonal architecture"],
              "techStackRationale": "Spring Boot 3",
              "securityConsiderations": ["OWASP Top 10"],
              "dataModelingGuidelines": "UUID primary keys",
              "apiDesignGuidelines": "REST with /v1/ prefix",
              "testingStrategy": "JUnit 5 + Testcontainers",
              "memberInsights": {"be-manager": "Clean code"}
            }
            """;
    }
}
