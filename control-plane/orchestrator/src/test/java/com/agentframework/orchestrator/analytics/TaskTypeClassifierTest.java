package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

class TaskTypeClassifierTest {

    private final TaskTypeClassifier classifier = new TaskTypeClassifier();

    @ParameterizedTest
    @DisplayName("classifies REASONING tasks")
    @CsvSource({
            "Implement authentication middleware, JWT token validation",
            "Design data model for user accounts, Schema with relations",
            "Add encryption for sensitive fields, AES-256 at rest",
            "Fix concurrency bug in task scheduler, Race condition on dispatch",
            "Implement access control for API endpoints, Role-based permissions"
    })
    void classifiesReasoning(String title, String description) {
        assertThat(classifier.classify(title, description)).isEqualTo(CognitiveTaskType.REASONING);
    }

    @ParameterizedTest
    @DisplayName("classifies VERIFICATION tasks")
    @CsvSource({
            "Write unit tests for OrderService, Cover edge cases",
            "Review pull request #42, Code quality check",
            "Add integration test coverage, CI/CD pipeline",
            "Run regression tests, Verify no breakage"
    })
    void classifiesVerification(String title, String description) {
        assertThat(classifier.classify(title, description)).isEqualTo(CognitiveTaskType.VERIFICATION);
    }

    @ParameterizedTest
    @DisplayName("classifies EXPLORATION tasks")
    @CsvSource({
            "Research caching strategies, Evaluate Redis vs Memcached",
            "Prototype new dashboard layout, Spike on D3.js",
            "Investigate memory leak, Experiment with profiler",
            "Feasibility study for WebSocket approach, Compare options"
    })
    void classifiesExploration(String title, String description) {
        assertThat(classifier.classify(title, description)).isEqualTo(CognitiveTaskType.EXPLORATION);
    }

    @ParameterizedTest
    @DisplayName("classifies STYLE tasks")
    @CsvSource({
            "Rename variables to camelCase, Code style compliance",
            "Fix indentation in controller, Whitespace cleanup",
            "Add javadoc to public API, Documentation",
            "Fix typo in error messages, Cosmetic fix"
    })
    void classifiesStyle(String title, String description) {
        assertThat(classifier.classify(title, description)).isEqualTo(CognitiveTaskType.STYLE);
    }

    @Test
    @DisplayName("defaults to REASONING when no keywords match")
    void defaultsToReasoning() {
        assertThat(classifier.classify("Build REST API", "Create endpoints"))
                .isEqualTo(CognitiveTaskType.REASONING);
    }

    @Test
    @DisplayName("handles null description")
    void handlesNullDescription() {
        var result = classifier.classify("Fix authentication bug", null);
        assertThat(result).isEqualTo(CognitiveTaskType.REASONING);
    }

    @Test
    @DisplayName("REASONING has lowest sigma threshold")
    void reasoningHasLowestSigmaThreshold() {
        assertThat(CognitiveTaskType.REASONING.sigmaThreshold())
                .isLessThan(CognitiveTaskType.STYLE.sigmaThreshold());
        assertThat(CognitiveTaskType.REASONING.sigmaThreshold())
                .isLessThan(CognitiveTaskType.EXPLORATION.sigmaThreshold());
    }

    @Test
    @DisplayName("only REASONING requires mandatory review")
    void onlyReasoningRequiresMandatoryReview() {
        assertThat(CognitiveTaskType.REASONING.reviewMandatory()).isTrue();
        assertThat(CognitiveTaskType.STYLE.reviewMandatory()).isFalse();
        assertThat(CognitiveTaskType.VERIFICATION.reviewMandatory()).isFalse();
        assertThat(CognitiveTaskType.EXPLORATION.reviewMandatory()).isFalse();
    }
}
