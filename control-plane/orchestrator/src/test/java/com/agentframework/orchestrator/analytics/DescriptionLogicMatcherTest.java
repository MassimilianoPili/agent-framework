package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link DescriptionLogicMatcher}.
 *
 * <p>Covers: input validation, ABox direct capability match, TBox subsumption path
 * (BFS up the hierarchy), no-match case, concept satisfiability, and the static
 * TBOX structure (be-java ⊑ jvm ⊑ worker).</p>
 *
 * <p>No Mockito needed — DescriptionLogicMatcher has zero repository dependencies
 * and can be instantiated directly.</p>
 */
class DescriptionLogicMatcherTest {

    private DescriptionLogicMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new DescriptionLogicMatcher();
    }

    // ── Input validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null requiredCapability throws IllegalArgumentException")
    void match_nullCapability_throws() {
        assertThatThrownBy(() -> matcher.match(null, Map.of("be-java", Set.of("spring"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank requiredCapability throws IllegalArgumentException")
    void match_blankCapability_throws() {
        assertThatThrownBy(() -> matcher.match("  ", Map.of("be-java", Set.of("spring"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null workerCapabilities throws IllegalArgumentException")
    void match_nullWorkerCapabilities_throws() {
        assertThatThrownBy(() -> matcher.match("backend", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("empty workerCapabilities throws IllegalArgumentException")
    void match_emptyWorkerCapabilities_throws() {
        assertThatThrownBy(() -> matcher.match("backend", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── ABox direct match ─────────────────────────────────────────────────────

    @Test
    @DisplayName("worker with exact capability in ABox → matched directly")
    void match_aboxDirectMatch_workerIncluded() {
        Map<String, Set<String>> caps = Map.of(
                "worker-A", Set.of("spring-boot", "kafka"),
                "worker-B", Set.of("react", "typescript")
        );

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("spring-boot", caps);

        assertThat(report.matchedWorkers()).containsExactly("worker-A");
        assertThat(report.subsumptionPaths()).containsKey("worker-A");
        assertThat(report.subsumptionPaths().get("worker-A"))
                .anyMatch(p -> p.contains("hasCapability"));
        assertThat(report.satisfiable()).isTrue();
    }

    // ── TBox subsumption match ────────────────────────────────────────────────

    @Test
    @DisplayName("be-java ⊑ jvm via TBox — matching 'jvm' finds be-java worker")
    void match_tboxSubsumption_beJavaSubsumesJvm() {
        // Worker "dev-1" is identified as "be-java" in the ABox key name
        // The TBox defines: be-java ⊑ jvm ⊑ worker
        Map<String, Set<String>> caps = Map.of(
                "be-java", Set.of("spring-boot"),   // no direct 'jvm' assertion
                "fe-react", Set.of("react")
        );

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("jvm", caps);

        // be-java ⊑ jvm → should be matched via TBox path
        assertThat(report.matchedWorkers()).contains("be-java");
        assertThat(report.subsumptionPaths().get("be-java"))
                .anyMatch(p -> p.contains("⊑"));
    }

    @Test
    @DisplayName("be-kotlin ⊑ backend ⊑ worker — matching 'backend' finds be-kotlin")
    void match_tboxSubsumption_beKotlinSubsumesBackend() {
        Map<String, Set<String>> caps = Map.of(
                "be-kotlin", Set.of("ktor"),
                "dba-postgres", Set.of("sql")
        );

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("backend", caps);

        assertThat(report.matchedWorkers()).contains("be-kotlin");
        assertThat(report.matchedWorkers()).doesNotContain("dba-postgres");
    }

    // ── No match ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unknown capability → empty matchedWorkers, explanation says no match")
    void match_unknownCapability_noMatch() {
        Map<String, Set<String>> caps = Map.of(
                "worker-A", Set.of("kafka")
        );

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("quantum-computing", caps);

        assertThat(report.matchedWorkers()).isEmpty();
        assertThat(report.explanation()).contains("No worker satisfies");
    }

    // ── Satisfiability ────────────────────────────────────────────────────────

    @Test
    @DisplayName("concept in TBox is satisfiable — 'worker' always satisfiable")
    void match_workerConcept_alwaysSatisfiable() {
        Map<String, Set<String>> caps = Map.of("be-go", Set.of("grpc"));

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("worker", caps);

        assertThat(report.satisfiable()).isTrue();
    }

    @Test
    @DisplayName("concept not in TBox and not in ABox → not satisfiable")
    void match_unsatisfiableConcept_satisfiableFalse() {
        Map<String, Set<String>> caps = Map.of("worker-A", Set.of("kafka"));

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("unicorn-engine", caps);

        assertThat(report.satisfiable()).isFalse();
    }

    // ── Case insensitivity ────────────────────────────────────────────────────

    @Test
    @DisplayName("capability matching is case-insensitive")
    void match_caseInsensitive_matched() {
        Map<String, Set<String>> caps = Map.of("w1", Set.of("Spring-Boot"));

        DescriptionLogicMatcher.DLMatchReport report = matcher.match("spring-boot", caps);

        assertThat(report.matchedWorkers()).contains("w1");
    }
}
