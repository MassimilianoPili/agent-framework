package com.agentframework.orchestrator.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StaleDetectorProperties} (#29 Fase 1).
 *
 * Verifies per-workerType timeout resolution and default fallback.
 */
class StaleDetectorPropertiesTest {

    @Test
    void timeoutFor_knownWorkerType_returnsSpecificTimeout() {
        var props = new StaleDetectorProperties(30, Map.of("AI_TASK", 120));

        assertThat(props.timeoutFor("AI_TASK")).isEqualTo(120);
    }

    @Test
    void timeoutFor_unknownWorkerType_returnsDefault() {
        var props = new StaleDetectorProperties(30, Map.of("AI_TASK", 120));

        // "REVIEW" not in workerTimeouts → falls back to defaultTimeoutMinutes
        assertThat(props.timeoutFor("REVIEW")).isEqualTo(30);
    }

    @Test
    void maxTimeoutMinutes_returnsMaxAcrossAllEntries() {
        var props = new StaleDetectorProperties(30, Map.of(
            "AI_TASK", 120,
            "HOOK_MANAGER", 10,
            "CONTEXT_MANAGER", 15
        ));

        assertThat(props.maxTimeoutMinutes()).isEqualTo(120);
    }

    @Test
    void maxTimeoutMinutes_emptyWorkerTimeouts_returnsDefault() {
        var props = new StaleDetectorProperties(45, Map.of());

        assertThat(props.maxTimeoutMinutes()).isEqualTo(45);
    }

    @Test
    void canonicalConstructor_nonPositiveDefault_resetsToThirty() {
        // defaultTimeoutMinutes ≤ 0 is invalid; canonical constructor resets it to 30
        var props = new StaleDetectorProperties(0, Map.of());

        assertThat(props.defaultTimeoutMinutes()).isEqualTo(30);
    }
}
