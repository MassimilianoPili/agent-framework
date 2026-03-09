package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.messaging.dto.AgentTask;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for #20 — model ID routing from PlanItem through AgentTask to the worker.
 *
 * Verifies that the modelId field is correctly propagated through the AgentTask DTO
 * and is null-safe when not specified.
 */
class ModelRoutingTest {

    @Test
    void agentTask_withModelId_preservesValue() {
        String expectedModel = "claude-haiku-4-5-20251001";

        AgentTask task = buildTask(expectedModel);

        assertThat(task.modelId()).isEqualTo(expectedModel);
    }

    @Test
    void agentTask_withNullModelId_returnsNull() {
        AgentTask task = buildTask(null);

        assertThat(task.modelId()).isNull();
    }

    @Test
    void agentTask_withBlankModelId_preservesBlank() {
        // Blank strings are valid at the DTO level — the factory handles them defensively
        AgentTask task = buildTask("  ");

        assertThat(task.modelId()).isEqualTo("  ");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AgentTask buildTask(String modelId) {
        return new AgentTask(
            UUID.randomUUID(),         // planId
            UUID.randomUUID(),         // itemId
            "BE-001",                  // taskKey
            "Build user service",      // title
            "Implement REST CRUD",     // description
            WorkerType.BE,             // workerType
            "be-java",                 // workerProfile
            null,                      // specSnippet
            null,                      // contextJson
            1,                         // attemptNumber
            UUID.randomUUID(),         // dispatchAttemptId
            UUID.randomUUID(),         // traceId
            "2025-01-01T00:00:00Z",    // dispatchedAt
            null,                      // policy
            null,                      // policyHash
            null,                      // councilContext
            null,                      // dynamicOwnsPaths
            null,                      // toolHints
            null,                      // workspacePath
            modelId                    // modelId (#20)
        );
    }
}
