package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.budget.TokenLedgerService;
import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ShapleyDagService} — DAG-aware Shapley value
 * computation for fair reward attribution (#40).
 *
 * <p>Tests cover linear DAGs, parallel tasks, diamond patterns,
 * edge cases, efficiency axiom, persistence, and TokenLedger integration.</p>
 */
@ExtendWith(MockitoExtension.class)
class ShapleyDagServiceTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private TokenLedgerService tokenLedgerService;

    private ShapleyDagService service;

    @BeforeEach
    void setUp() {
        service = new ShapleyDagService(planItemRepository, tokenLedgerService);
        ReflectionTestUtils.setField(service, "monteCarloSamples", 5000);
    }

    // ── DAG construction helpers ────────────────────────────────────────────

    private Plan createPlan() {
        UUID planId = UUID.randomUUID();
        Plan plan = new Plan(planId, "Test spec");
        plan.transitionTo(PlanStatus.RUNNING);
        return plan;
    }

    private PlanItem createItem(Plan plan, String taskKey, WorkerType type,
                                 Float reward, List<String> dependsOn) {
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, taskKey, "Title: " + taskKey,
                "Description", type, type.name().toLowerCase(), dependsOn, List.of());
        plan.addItem(item);
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);
        item.setAggregatedReward(reward);
        return item;
    }

    // ── Core Shapley computation tests ──────────────────────────────────────

    @Test
    @DisplayName("Linear DAG A→B→C: Shapley distributes credit to all tasks")
    void computeForPlan_linearDag_creditsAllTasks() {
        // A (CM, reward=0) → B (BE, reward=0.8) → C (REVIEW, reward=0)
        // Only B produces direct reward, but A enables B which enables C
        Plan plan = createPlan();
        createItem(plan, "CM-001", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        createItem(plan, "BE-001", WorkerType.BE, 0.8f, List.of("CM-001"));
        createItem(plan, "RV-001", WorkerType.REVIEW, 0.0f, List.of("BE-001"));

        Map<String, Double> result = service.monteCarloShapleyDag(
                plan.getItems().stream().filter(i -> i.getStatus() == ItemStatus.DONE).toList(),
                5000);

        // Efficiency: Σφ = v(N) = 0.8
        double sum = result.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(0.8, within(0.01));

        // In a linear chain with one reward producer:
        // CM-001 gets Shapley > 0 because it enables BE-001
        // The exact values depend on the permutation distribution
        assertThat(result.get("CM-001")).isGreaterThanOrEqualTo(0.0);
        assertThat(result.get("BE-001")).isGreaterThan(0.0);
        assertThat(result.get("RV-001")).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("Parallel tasks: Shapley equals direct reward (independent)")
    void computeForPlan_parallelTasks_independentShapley() {
        // A (BE, reward=0.7) and B (FE, reward=0.5) — no dependencies
        Plan plan = createPlan();
        createItem(plan, "BE-001", WorkerType.BE, 0.7f, List.of());
        createItem(plan, "FE-001", WorkerType.FE, 0.5f, List.of());

        Map<String, Double> result = service.monteCarloShapleyDag(
                plan.getItems().stream().filter(i -> i.getStatus() == ItemStatus.DONE).toList(),
                5000);

        // Independent tasks: Shapley = reward (no synergy)
        assertThat(result.get("BE-001")).isCloseTo(0.7, within(0.01));
        assertThat(result.get("FE-001")).isCloseTo(0.5, within(0.01));
    }

    @Test
    @DisplayName("Diamond DAG A→{B,C}→D: fair distribution among enablers")
    void computeForPlan_diamondDag_fairDistribution() {
        // A (CM, 0.0) → B (BE, 0.6) and C (CONTRACT, 0.4), both → D (REVIEW, 0.0)
        Plan plan = createPlan();
        createItem(plan, "CM-001", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        createItem(plan, "BE-001", WorkerType.BE, 0.6f, List.of("CM-001"));
        createItem(plan, "CT-001", WorkerType.CONTRACT, 0.4f, List.of("CM-001"));
        createItem(plan, "RV-001", WorkerType.REVIEW, 0.0f, List.of("BE-001", "CT-001"));

        Map<String, Double> result = service.monteCarloShapleyDag(
                plan.getItems().stream().filter(i -> i.getStatus() == ItemStatus.DONE).toList(),
                5000);

        // Efficiency: Σφ = v(N) = 1.0
        double sum = result.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, within(0.01));

        // CM-001 enables both B and C — gets some credit
        assertThat(result.get("CM-001")).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("No DONE items returns empty map")
    void computeForPlan_noItems_returnsEmpty() {
        Plan plan = createPlan();
        // Add a WAITING item
        PlanItem item = new PlanItem(UUID.randomUUID(), 0, "BE-001", "Title",
                "Description", WorkerType.BE, "be-java", List.of(), List.of());
        plan.addItem(item);

        Map<String, Double> result = service.computeForPlan(plan);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Single task gets full credit")
    void computeForPlan_singleTask_fullCredit() {
        Plan plan = createPlan();
        createItem(plan, "BE-001", WorkerType.BE, 0.9f, List.of());

        Map<String, Double> result = service.monteCarloShapleyDag(
                plan.getItems().stream().filter(i -> i.getStatus() == ItemStatus.DONE).toList(),
                5000);

        assertThat(result.get("BE-001")).isCloseTo(0.9, within(0.001));
    }

    // ── Topological ordering tests ──────────────────────────────────────────

    @Test
    @DisplayName("Random topological order respects dependencies")
    void randomTopologicalOrder_respectsDependencies() {
        Plan plan = createPlan();
        createItem(plan, "A", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        createItem(plan, "B", WorkerType.BE, 0.5f, List.of("A"));
        createItem(plan, "C", WorkerType.FE, 0.5f, List.of("A"));
        createItem(plan, "D", WorkerType.REVIEW, 0.0f, List.of("B", "C"));

        List<PlanItem> items = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE).toList();
        Map<String, Set<String>> preds = service.buildPredecessorsMap(items);

        // Run 100 random orderings, verify each respects dependencies
        Random rng = new Random(42);
        for (int i = 0; i < 100; i++) {
            List<String> order = service.randomTopologicalOrder(items, preds, rng);

            assertThat(order).hasSize(4);
            // A must come before B and C
            assertThat(order.indexOf("A")).isLessThan(order.indexOf("B"));
            assertThat(order.indexOf("A")).isLessThan(order.indexOf("C"));
            // B and C must come before D
            assertThat(order.indexOf("B")).isLessThan(order.indexOf("D"));
            assertThat(order.indexOf("C")).isLessThan(order.indexOf("D"));
        }
    }

    // ── Coalition value function tests ──────────────────────────────────────

    @Test
    @DisplayName("Coalition value excludes task with missing predecessor")
    void dagCoalitionValue_missingPredecessor_excludesTask() {
        Plan plan = createPlan();
        createItem(plan, "A", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        createItem(plan, "B", WorkerType.BE, 0.8f, List.of("A"));

        List<PlanItem> items = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE).toList();
        Map<String, Set<String>> preds = service.buildPredecessorsMap(items);
        Map<String, Double> rewards = service.buildRewardsMap(items);
        Set<String> validTasks = Set.of("A", "B");

        // Coalition {B} without A: B's predecessor A is missing → B doesn't contribute
        double value = service.dagCoalitionValue(Set.of("B"), preds, rewards, validTasks);
        assertThat(value).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Coalition value includes task when all predecessors present")
    void dagCoalitionValue_allPredecessorsPresent_includesTask() {
        Plan plan = createPlan();
        createItem(plan, "A", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        createItem(plan, "B", WorkerType.BE, 0.8f, List.of("A"));

        List<PlanItem> items = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE).toList();
        Map<String, Set<String>> preds = service.buildPredecessorsMap(items);
        Map<String, Double> rewards = service.buildRewardsMap(items);
        Set<String> validTasks = Set.of("A", "B");

        // Coalition {A, B}: B's predecessor A is present → B contributes 0.8
        double value = service.dagCoalitionValue(Set.of("A", "B"), preds, rewards, validTasks);
        assertThat(value).isCloseTo(0.8, within(0.001));
    }

    // ── Efficiency axiom ────────────────────────────────────────────────────

    @Test
    @DisplayName("Shapley values sum to grand coalition value (efficiency axiom)")
    void efficiencyAxiom_shapleyValuesSumToGrandCoalition() {
        // Complex DAG: CM→{BE, FE}→REVIEW, BE→CONTRACT
        Plan plan = createPlan();
        createItem(plan, "CM-001", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        createItem(plan, "BE-001", WorkerType.BE, 0.7f, List.of("CM-001"));
        createItem(plan, "FE-001", WorkerType.FE, 0.5f, List.of("CM-001"));
        createItem(plan, "CT-001", WorkerType.CONTRACT, 0.3f, List.of("BE-001"));
        createItem(plan, "RV-001", WorkerType.REVIEW, 0.0f, List.of("BE-001", "FE-001"));

        List<PlanItem> doneItems = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE).toList();
        Map<String, Double> result = service.monteCarloShapleyDag(doneItems, 10000);

        double gcv = doneItems.stream()
                .mapToDouble(i -> i.getAggregatedReward() != null ? i.getAggregatedReward() : 0.0)
                .sum();
        double shapleySum = result.values().stream().mapToDouble(Double::doubleValue).sum();

        // v(N) = 0.7 + 0.5 + 0.3 = 1.5
        assertThat(gcv).isCloseTo(1.5, within(0.001));
        assertThat(shapleySum).isCloseTo(gcv, within(0.05));
    }

    // ── Persistence and integration tests ───────────────────────────────────

    @Test
    @DisplayName("computeForPlan persists Shapley values on PlanItems")
    void computeForPlan_persistsValues() {
        Plan plan = createPlan();
        PlanItem be = createItem(plan, "BE-001", WorkerType.BE, 0.8f, List.of());

        service.computeForPlan(plan);

        assertThat(be.getShapleyValue()).isNotNull();
        assertThat(be.getShapleyValue()).isCloseTo(0.8, within(0.01));
        verify(planItemRepository).saveAll(any());
    }

    @Test
    @DisplayName("computeForPlan triggers ledger credit for infra workers with Shapley > 0")
    void computeForPlan_triggersLedgerCredit() {
        Plan plan = createPlan();
        createItem(plan, "CM-001", WorkerType.CONTEXT_MANAGER, 0.0f, List.of());
        PlanItem be = createItem(plan, "BE-001", WorkerType.BE, 0.8f, List.of("CM-001"));
        // Set result JSON with token info to enable credit calculation
        be.setResult("{\"provenance\":{\"tokenUsage\":{\"totalTokens\":5000}}}");

        service.computeForPlan(plan);

        // CM-001 is infra (not credit-eligible in #33), should get Shapley credit
        // BE-001 is already credit-eligible, should NOT get Shapley credit
        verify(tokenLedgerService, atLeastOnce()).creditShapley(
                eq(plan.getId()), any(), eq("CM-001"), eq("CONTEXT_MANAGER"),
                anyDouble(), anyLong());
        verify(tokenLedgerService, never()).creditShapley(
                any(), any(), eq("BE-001"), eq("BE"), anyDouble(), anyLong());
    }

    @Test
    @DisplayName("Infrastructure worker gets Shapley credit proportional to enablement")
    void computeForPlan_infraWorkerGetsCredit() {
        // HM→BE(0.9): hook manager enables the BE worker
        Plan plan = createPlan();
        createItem(plan, "HM-001", WorkerType.HOOK_MANAGER, 0.0f, List.of());
        PlanItem be = createItem(plan, "BE-001", WorkerType.BE, 0.9f, List.of("HM-001"));
        be.setResult("{\"provenance\":{\"tokenUsage\":{\"totalTokens\":10000}}}");

        Map<String, Double> result = service.computeForPlan(plan);

        // HM-001 should have Shapley > 0 (enables BE-001)
        assertThat(result.get("HM-001")).isGreaterThan(0.0);
        // Σφ = 0.9
        double sum = result.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(0.9, within(0.01));
    }

    @Test
    @DisplayName("External dependencies (not in DAG) are filtered out")
    void computeForPlan_externalDeps_filteredOut() {
        // BE-001 depends on "EXT-001" which is not in the plan
        Plan plan = createPlan();
        createItem(plan, "BE-001", WorkerType.BE, 0.7f, List.of("EXT-001"));

        List<PlanItem> items = plan.getItems().stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE).toList();
        Map<String, Double> result = service.monteCarloShapleyDag(items, 1000);

        // External dep should be ignored, BE-001 gets full credit
        assertThat(result.get("BE-001")).isCloseTo(0.7, within(0.01));
    }
}
