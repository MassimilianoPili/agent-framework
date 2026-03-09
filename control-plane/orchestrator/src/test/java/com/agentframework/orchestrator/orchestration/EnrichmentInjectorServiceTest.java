package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.config.EnrichmentProperties;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EnrichmentInjectorService} — enrichment pipeline auto-injection
 * of CONTEXT_MANAGER, RAG_MANAGER, SCHEMA_MANAGER, and TOOL_MANAGER tasks into plans.
 */
class EnrichmentInjectorServiceTest {

    // ── Default config: auto-inject ON, CM ON, RAG ON, Schema OFF, TM OFF ────

    private static final EnrichmentProperties DEFAULT_PROPS =
            new EnrichmentProperties(true, true, true, false, false);

    // ── inject: adds CM and RM to plan without enrichment ────────────────────

    @Test
    void inject_planWithoutEnrichment_addsCmAndRm() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Implement user authentication");
        addItem(plan, "CT-001", WorkerType.CONTRACT, 0, List.of());
        addItem(plan, "BE-001", WorkerType.BE, 1, List.of("CT-001"));
        addItem(plan, "RV-001", WorkerType.REVIEW, 2, List.of("BE-001"));

        service.inject(plan);

        // Should have 5 items now: CM-001, RM-001, CT-001, BE-001, RV-001
        assertThat(plan.getItems()).hasSize(5);
        assertThat(findByKey(plan, "CM-001")).isNotNull();
        assertThat(findByKey(plan, "CM-001").getWorkerType()).isEqualTo(WorkerType.CONTEXT_MANAGER);
        assertThat(findByKey(plan, "RM-001")).isNotNull();
        assertThat(findByKey(plan, "RM-001").getWorkerType()).isEqualTo(WorkerType.RAG_MANAGER);
    }

    // ── inject: idempotent — existing CM not duplicated ──────────────────────

    @Test
    void inject_alreadyHasCm_skipsCmAddsRm() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Feature with existing context manager");
        addItem(plan, "CM-001", WorkerType.CONTEXT_MANAGER, 0, List.of());
        addItem(plan, "BE-001", WorkerType.BE, 1, List.of("CM-001"));

        service.inject(plan);

        // CM not duplicated — only RM added
        long cmCount = plan.getItems().stream()
                .filter(i -> i.getWorkerType() == WorkerType.CONTEXT_MANAGER).count();
        assertThat(cmCount).isEqualTo(1);
        assertThat(findByKey(plan, "RM-001")).isNotNull();
        // RM should depend on existing CM
        assertThat(findByKey(plan, "RM-001").getDependsOn()).contains("CM-001");
    }

    // ── inject: existing RM but no CM → adds CM only ─────────────────────────

    @Test
    void inject_alreadyHasRmButNoCm_addsCmOnly() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Plan with RM but no CM");
        addItem(plan, "RM-001", WorkerType.RAG_MANAGER, 0, List.of());
        addItem(plan, "BE-001", WorkerType.BE, 1, List.of("RM-001"));

        service.inject(plan);

        // CM added, RM not duplicated
        assertThat(findByKey(plan, "CM-001")).isNotNull();
        long rmCount = plan.getItems().stream()
                .filter(i -> i.getWorkerType() == WorkerType.RAG_MANAGER).count();
        assertThat(rmCount).isEqualTo(1);
    }

    // ── inject: RAG disabled → only CM ───────────────────────────────────────

    @Test
    void inject_ragDisabled_onlyCm() {
        EnrichmentProperties noRag = new EnrichmentProperties(true, true, false, false, false);
        EnrichmentInjectorService service = new EnrichmentInjectorService(noRag);
        Plan plan = planWithSpec("Small task");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        assertThat(findByKey(plan, "CM-001")).isNotNull();
        assertThat(findByKey(plan, "RM-001")).isNull();
        assertThat(plan.getItems()).hasSize(2); // CM + BE
    }

    // ── inject: domain workers get new dependencies ──────────────────────────

    @Test
    void inject_domainWorkersGetNewDeps() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Multi-worker plan");
        addItem(plan, "CT-001", WorkerType.CONTRACT, 0, List.of());
        addItem(plan, "BE-001", WorkerType.BE, 1, List.of("CT-001"));
        addItem(plan, "FE-001", WorkerType.FE, 1, List.of("CT-001"));
        addItem(plan, "RV-001", WorkerType.REVIEW, 2, List.of("BE-001", "FE-001"));

        service.inject(plan);

        // Domain workers (CT, BE, FE) should have CM-001 and RM-001 as dependencies
        assertThat(findByKey(plan, "CT-001").getDependsOn()).contains("CM-001", "RM-001");
        assertThat(findByKey(plan, "BE-001").getDependsOn()).contains("CM-001", "RM-001");
        assertThat(findByKey(plan, "FE-001").getDependsOn()).contains("CM-001", "RM-001");

        // REVIEW should NOT get enrichment dependencies (non-domain type)
        assertThat(findByKey(plan, "RV-001").getDependsOn()).doesNotContain("CM-001", "RM-001");
    }

    // ── inject: AI_TASK and DBA also get enrichment deps ─────────────────────

    @Test
    void inject_allDomainTypesGetDeps() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Mixed domain types");
        addItem(plan, "AI-001", WorkerType.AI_TASK, 0, List.of());
        addItem(plan, "DB-001", WorkerType.DBA, 0, List.of());

        service.inject(plan);

        assertThat(findByKey(plan, "AI-001").getDependsOn()).contains("CM-001", "RM-001");
        assertThat(findByKey(plan, "DB-001").getDependsOn()).contains("CM-001", "RM-001");
    }

    // ── inject: auto-inject disabled → no-op ─────────────────────────────────

    @Test
    void inject_autoInjectDisabled_noOp() {
        EnrichmentProperties disabled = new EnrichmentProperties(false, true, true, false, false);
        EnrichmentInjectorService service = new EnrichmentInjectorService(disabled);
        Plan plan = planWithSpec("Should not be enriched");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        assertThat(plan.getItems()).hasSize(1);
        assertThat(findByKey(plan, "CM-001")).isNull();
    }

    // ── inject: schema enabled → adds SM ─────────────────────────────────────

    @Test
    void inject_schemaEnabled_addsSm() {
        EnrichmentProperties withSchema = new EnrichmentProperties(true, true, true, true, false);
        EnrichmentInjectorService service = new EnrichmentInjectorService(withSchema);
        Plan plan = planWithSpec("API-heavy feature");
        addItem(plan, "CT-001", WorkerType.CONTRACT, 0, List.of());
        addItem(plan, "BE-001", WorkerType.BE, 1, List.of("CT-001"));

        service.inject(plan);

        assertThat(findByKey(plan, "CM-001")).isNotNull();
        assertThat(findByKey(plan, "RM-001")).isNotNull();
        assertThat(findByKey(plan, "SM-001")).isNotNull();
        assertThat(findByKey(plan, "SM-001").getWorkerType()).isEqualTo(WorkerType.SCHEMA_MANAGER);
        // SM depends on CM
        assertThat(findByKey(plan, "SM-001").getDependsOn()).contains("CM-001");
        // Domain workers have all 3 enrichment deps
        assertThat(findByKey(plan, "BE-001").getDependsOn())
                .contains("CM-001", "RM-001", "SM-001");
    }

    // ── inject: empty plan → no-op (no items to enrich) ──────────────────────

    @Test
    void inject_emptyPlan_noOp() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Empty plan");

        service.inject(plan);

        assertThat(plan.getItems()).isEmpty();
    }

    // ── inject: RM depends on CM when CM is injected ─────────────────────────

    @Test
    void inject_rmDependsOnCm() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Check RM→CM dependency");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        PlanItem rm = findByKey(plan, "RM-001");
        assertThat(rm).isNotNull();
        assertThat(rm.getDependsOn()).containsExactly("CM-001");

        PlanItem cm = findByKey(plan, "CM-001");
        assertThat(cm).isNotNull();
        assertThat(cm.getDependsOn()).isEmpty();
    }

    // ── inject: CM description includes plan spec (truncated) ────────────────

    @Test
    void inject_cmDescriptionIncludesPlanSpec() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("Implement OAuth2 PKCE flow for mobile clients");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        PlanItem cm = findByKey(plan, "CM-001");
        assertThat(cm.getDescription()).contains("OAuth2 PKCE flow");
    }

    // ── TOOL_MANAGER injection tests (#24 L2) ────────────────────────────────

    private static final EnrichmentProperties TM_PROPS =
            new EnrichmentProperties(true, true, true, false, true);

    @Test
    void inject_toolManagerEnabled_addsTmPerDomainWorker() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(TM_PROPS);
        Plan plan = planWithSpec("Multi-worker plan with TM");
        addItem(plan, "CT-001", WorkerType.CONTRACT, 0, List.of());
        addItem(plan, "BE-001", WorkerType.BE, 1, List.of("CT-001"));
        addItem(plan, "FE-001", WorkerType.FE, 1, List.of("CT-001"));
        addItem(plan, "RV-001", WorkerType.REVIEW, 2, List.of("BE-001", "FE-001"));

        service.inject(plan);

        // Original 4 + CM-001 + RM-001 + TM-CT-001 + TM-BE-001 + TM-FE-001 + TM-RV-001 = 10
        assertThat(plan.getItems()).hasSize(10);
        assertThat(findByKey(plan, "TM-CT-001")).isNotNull();
        assertThat(findByKey(plan, "TM-BE-001")).isNotNull();
        assertThat(findByKey(plan, "TM-FE-001")).isNotNull();
        assertThat(findByKey(plan, "TM-RV-001")).isNotNull();

        // All TM items are TOOL_MANAGER type
        assertThat(findByKey(plan, "TM-BE-001").getWorkerType()).isEqualTo(WorkerType.TOOL_MANAGER);
    }

    @Test
    void inject_toolManagerEnabled_tmDependsOnCmAndRm() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(TM_PROPS);
        Plan plan = planWithSpec("TM dependency check");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        PlanItem tm = findByKey(plan, "TM-BE-001");
        assertThat(tm).isNotNull();
        assertThat(tm.getDependsOn()).contains("CM-001", "RM-001");
    }

    @Test
    void inject_toolManagerEnabled_domainDependsOnTm() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(TM_PROPS);
        Plan plan = planWithSpec("Domain → TM dependency check");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());
        addItem(plan, "FE-001", WorkerType.FE, 0, List.of());

        service.inject(plan);

        // BE-001 should depend on CM-001, RM-001 (enrichment), and TM-BE-001 (tool manager)
        assertThat(findByKey(plan, "BE-001").getDependsOn())
                .contains("CM-001", "RM-001", "TM-BE-001");
        assertThat(findByKey(plan, "FE-001").getDependsOn())
                .contains("CM-001", "RM-001", "TM-FE-001");
    }

    @Test
    void inject_toolManagerDisabled_noTmTasks() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(DEFAULT_PROPS);
        Plan plan = planWithSpec("No TM tasks");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        // No TM-* tasks when includeToolManager is false
        assertThat(findByKey(plan, "TM-BE-001")).isNull();
        long tmCount = plan.getItems().stream()
                .filter(i -> i.getWorkerType() == WorkerType.TOOL_MANAGER).count();
        assertThat(tmCount).isZero();
    }

    @Test
    void inject_toolManagerEnabled_tmDescriptionIncludesTargetKey() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(TM_PROPS);
        Plan plan = planWithSpec("TM description check");
        addItem(plan, "BE-001", WorkerType.BE, 0, List.of());

        service.inject(plan);

        PlanItem tm = findByKey(plan, "TM-BE-001");
        assertThat(tm).isNotNull();
        assertThat(tm.getDescription()).contains("Target task key: BE-001");
        assertThat(tm.getDescription()).contains("BE");
    }

    @Test
    void inject_toolManagerEnabled_reviewAlsoGetsTm() {
        EnrichmentInjectorService service = new EnrichmentInjectorService(TM_PROPS);
        Plan plan = planWithSpec("REVIEW gets TM too");
        addItem(plan, "RV-001", WorkerType.REVIEW, 0, List.of());

        service.inject(plan);

        // REVIEW is in TOOL_MANAGER_TARGET_TYPES
        assertThat(findByKey(plan, "TM-RV-001")).isNotNull();
        assertThat(findByKey(plan, "RV-001").getDependsOn()).contains("TM-RV-001");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Plan planWithSpec(String spec) {
        return new Plan(UUID.randomUUID(), spec);
    }

    private static void addItem(Plan plan, String taskKey, WorkerType type,
                                int ordinal, List<String> deps) {
        PlanItem item = new PlanItem(
                UUID.randomUUID(), ordinal, taskKey,
                "Title for " + taskKey,
                "Description for " + taskKey,
                type, null, deps, List.of()
        );
        plan.addItem(item);
    }

    private static PlanItem findByKey(Plan plan, String taskKey) {
        return plan.getItems().stream()
                .filter(i -> i.getTaskKey().equals(taskKey))
                .findFirst()
                .orElse(null);
    }
}
