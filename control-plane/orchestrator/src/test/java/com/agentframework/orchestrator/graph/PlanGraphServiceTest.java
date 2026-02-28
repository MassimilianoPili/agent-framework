package com.agentframework.orchestrator.graph;

import com.agentframework.orchestrator.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link PlanGraphService} — Mermaid and JSON DAG generation.
 */
@ExtendWith(MockitoExtension.class)
class PlanGraphServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlanGraphService service;

    @BeforeEach
    void setUp() {
        service = new PlanGraphService();
    }

    // ── toMermaid ──────────────────────────────────────────────────────────────

    @Test
    void toMermaid_singleItemNoDeps_containsNodeAndClassDef() {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        plan.addItem(item);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid)
                .startsWith("graph LR")
                .contains("BE_001")                   // sanitized nodeId (hyphens → underscores)
                .contains("BE-001")                    // original taskKey in the label
                .contains("BE")                        // workerType
                .contains("be-java")                   // workerProfile
                .contains("WAITING")                   // default status
                .contains("classDef st_DONE")
                .contains("classDef st_FAILED")
                .contains("classDef st_RUNNING")
                .contains("classDef st_WAITING")
                .contains(":::st_WAITING");
    }

    @Test
    void toMermaid_threeItemChainWithEdgeLabels() {
        Plan plan = newPlan();

        PlanItem cm = newItem(1, "CM-001", WorkerType.CONTEXT_MANAGER, "cm-default", List.of());
        PlanItem be = newItem(2, "BE-001", WorkerType.BE, "be-java", List.of("CM-001"));
        PlanItem rv = newItem(3, "RV-001", WorkerType.REVIEW, "rv-default", List.of("BE-001"));

        plan.addItem(cm);
        plan.addItem(be);
        plan.addItem(rv);

        String mermaid = service.toMermaid(plan);

        // CM-001 → BE-001 with "context" label (CM- prefix)
        assertThat(mermaid).contains("CM_001 -->|context| BE_001");
        // BE-001 → RV-001 with no label (BE- prefix has no special mapping)
        assertThat(mermaid).contains("BE_001 --> RV_001");
        // Verify all three nodes are present
        assertThat(mermaid)
                .contains("CM_001[")
                .contains("BE_001[")
                .contains("RV_001[");
    }

    @Test
    void toMermaid_doneItemWithDurationAndTokens_showsFormattedStats() {
        Plan plan = newPlan();

        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.setDispatchedAt(Instant.parse("2026-01-01T10:00:00Z"));
        item.transitionTo(ItemStatus.DONE);
        item.setCompletedAt(Instant.parse("2026-01-01T10:00:45Z")); // 45 seconds
        item.setResult("{\"provenance\":{\"tokenUsage\":{\"totalTokens\":12300}}}");

        plan.addItem(item);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid)
                .contains("45s")                         // duration formatted
                .contains("12.3k tk")                    // tokens formatted (12300 → 12.3k)
                .contains(":::st_DONE");                 // status class
    }

    @Test
    void toMermaid_smDependency_showsSchemaLabel() {
        Plan plan = newPlan();

        PlanItem sm = newItem(1, "SM-001", WorkerType.SCHEMA_MANAGER, "sm-default", List.of());
        PlanItem be = newItem(2, "BE-001", WorkerType.BE, "be-java", List.of("SM-001"));
        plan.addItem(sm);
        plan.addItem(be);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid).contains("SM_001 -->|schema| BE_001");
    }

    @Test
    void toMermaid_hmDependency_showsPolicyLabel() {
        Plan plan = newPlan();

        PlanItem hm = newItem(1, "HM-001", WorkerType.HOOK_MANAGER, "hm-default", List.of());
        PlanItem be = newItem(2, "BE-001", WorkerType.BE, "be-java", List.of("HM-001"));
        plan.addItem(hm);
        plan.addItem(be);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid).contains("HM_001 -->|policy| BE_001");
    }

    // ── toJson ─────────────────────────────────────────────────────────────────

    @Test
    void toJson_singleItem_validJsonWithAllFields() throws Exception {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        plan.addItem(item);

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.has("planId")).isTrue();
        assertThat(root.get("planId").asText()).isEqualTo(plan.getId().toString());
        assertThat(root.get("status").asText()).isEqualTo("PENDING");

        JsonNode nodes = root.get("nodes");
        assertThat(nodes.isArray()).isTrue();
        assertThat(nodes).hasSize(1);

        JsonNode node = nodes.get(0);
        assertThat(node.get("taskKey").asText()).isEqualTo("BE-001");
        assertThat(node.get("workerType").asText()).isEqualTo("BE");
        assertThat(node.get("workerProfile").asText()).isEqualTo("be-java");
        assertThat(node.get("status").asText()).isEqualTo("WAITING");
        assertThat(node.get("durationMs").asLong()).isZero();
        assertThat(node.get("tokensUsed").asLong()).isZero();

        JsonNode edges = root.get("edges");
        assertThat(edges.isArray()).isTrue();
        assertThat(edges).isEmpty();
    }

    @Test
    void toJson_twoItemsWithDependency_edgesPopulated() throws Exception {
        Plan plan = newPlan();
        PlanItem cm = newItem(1, "CM-001", WorkerType.CONTEXT_MANAGER, "cm-default", List.of());
        PlanItem be = newItem(2, "BE-001", WorkerType.BE, "be-java", List.of("CM-001"));
        plan.addItem(cm);
        plan.addItem(be);

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        JsonNode nodes = root.get("nodes");
        assertThat(nodes).hasSize(2);

        JsonNode edges = root.get("edges");
        assertThat(edges).hasSize(1);

        JsonNode edge = edges.get(0);
        assertThat(edge.get("from").asText()).isEqualTo("CM-001");
        assertThat(edge.get("to").asText()).isEqualTo("BE-001");
        assertThat(edge.get("type").asText()).isEqualTo("context");
    }

    @Test
    void toJson_emptyPlan_returnsValidEmptyGraph() throws Exception {
        Plan plan = newPlan();

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("planId").asText()).isEqualTo(plan.getId().toString());
        assertThat(root.get("status").asText()).isEqualTo("PENDING");
        assertThat(root.get("nodes")).isEmpty();
        assertThat(root.get("edges")).isEmpty();
    }

    @Test
    void toMermaid_emptyPlan_returnsValidGraphHeader() {
        Plan plan = newPlan();

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid)
                .startsWith("graph LR")
                .contains("classDef st_DONE")
                .contains("classDef st_FAILED");
        // No node definitions beyond the classDefs
    }

    @Test
    void toJson_doneItemWithDuration_populatesDurationMs() throws Exception {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.setDispatchedAt(Instant.parse("2026-01-01T10:00:00Z"));
        item.transitionTo(ItemStatus.DONE);
        item.setCompletedAt(Instant.parse("2026-01-01T10:01:30Z")); // 90 seconds = 90000ms

        plan.addItem(item);

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        JsonNode node = root.get("nodes").get(0);
        assertThat(node.get("durationMs").asLong()).isEqualTo(90_000L);
    }

    @Test
    void toMermaid_longDuration_formatsAsMinutesAndSeconds() {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.setDispatchedAt(Instant.parse("2026-01-01T10:00:00Z"));
        item.transitionTo(ItemStatus.DONE);
        item.setCompletedAt(Instant.parse("2026-01-01T10:02:15Z")); // 2m15s

        plan.addItem(item);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid).contains("2m15s");
    }

    @Test
    void toMermaid_nullWorkerProfile_rendersWithoutProfile() {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "AI-001", WorkerType.AI_TASK, null, List.of());
        plan.addItem(item);

        String mermaid = service.toMermaid(plan);

        // Should contain the workerType without the " · profile" suffix
        assertThat(mermaid)
                .contains("AI_TASK")
                .doesNotContain(" · null");
    }

    @Test
    void toJson_nullWorkerProfile_returnsEmptyString() throws Exception {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "AI-001", WorkerType.AI_TASK, null, List.of());
        plan.addItem(item);

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("nodes").get(0).get("workerProfile").asText()).isEmpty();
    }

    @Test
    void toMermaid_nullResult_noTokensOrDurationInLabel() {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        // result is null by default, dispatchedAt/completedAt are null → no stats
        plan.addItem(item);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid)
                .doesNotContain(" tk")
                .doesNotContain("| ");
    }

    @Test
    void toJson_tokenExtraction_provenanceTotalTokensFallback() throws Exception {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);
        // Path 2: provenance.totalTokens (no tokenUsage wrapper)
        item.setResult("{\"provenance\":{\"totalTokens\":500}}");
        plan.addItem(item);

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("nodes").get(0).get("tokensUsed").asLong()).isEqualTo(500L);
    }

    @Test
    void toJson_tokenExtraction_flatTokenUsageFallback() throws Exception {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);
        // Path 3: tokenUsage.totalTokens (flat, no provenance wrapper)
        item.setResult("{\"tokenUsage\":{\"totalTokens\":250}}");
        plan.addItem(item);

        String json = service.toJson(plan);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.get("nodes").get(0).get("tokensUsed").asLong()).isEqualTo(250L);
    }

    @Test
    void toMermaid_smallTokenCount_formatsWithoutKSuffix() {
        Plan plan = newPlan();
        PlanItem item = newItem(1, "BE-001", WorkerType.BE, "be-java", List.of());
        item.transitionTo(ItemStatus.DISPATCHED);
        item.setDispatchedAt(Instant.parse("2026-01-01T10:00:00Z"));
        item.transitionTo(ItemStatus.DONE);
        item.setCompletedAt(Instant.parse("2026-01-01T10:00:10Z"));
        item.setResult("{\"provenance\":{\"tokenUsage\":{\"totalTokens\":750}}}");
        plan.addItem(item);

        String mermaid = service.toMermaid(plan);

        assertThat(mermaid).contains("750 tk");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static Plan newPlan() {
        return new Plan(UUID.randomUUID(), "test-spec");
    }

    private static PlanItem newItem(int ordinal, String taskKey, WorkerType workerType,
                                     String workerProfile, List<String> dependsOn) {
        return new PlanItem(UUID.randomUUID(), ordinal, taskKey, "Title " + taskKey,
                "Description for " + taskKey, workerType, workerProfile, dependsOn);
    }
}
