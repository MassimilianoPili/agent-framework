package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.config.EnrichmentProperties;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Post-decomposition enrichment injector — automatically inserts CONTEXT_MANAGER,
 * RAG_MANAGER, optionally SCHEMA_MANAGER, and per-task TOOL_MANAGER tasks as
 * dependencies of domain workers.
 *
 * <p>Called after {@link com.agentframework.orchestrator.planner.PlannerService#decompose(Plan)}
 * in {@link OrchestrationService#createAndStart(String, com.agentframework.orchestrator.api.PlanRequest.Budget)}.
 * Operates in-memory on the Plan before it is persisted.</p>
 *
 * <p><b>Idempotent</b>: if the planner already generated enrichment tasks (Level 1),
 * they are not duplicated. The check is based on {@link PlanItem#getWorkerType()}.</p>
 *
 * <p><b>Ordinals</b>: enrichment items use ordinal 0 (CM) and 1 (RM, SM, TM). Existing
 * items keep their original ordinals. Dispatch order is controlled by the dependency
 * DAG ({@code findDispatchableItems}), not by ordinals — ordinals are cosmetic only.</p>
 *
 * <p><b>TOOL_MANAGER pattern</b> (#24 L2): unlike CM/RM/SM which are plan-level singletons,
 * TOOL_MANAGER is injected once <em>per domain task</em> (fan-out pattern). Each TM-* task
 * depends on CM + RM (to read their outputs) and its target domain task depends on the
 * corresponding TM-* task. This ensures per-task tool policy is computed before dispatch.</p>
 */
@Service
public class EnrichmentInjectorService {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentInjectorService.class);

    /**
     * Domain worker types that benefit from enrichment context as dependencies.
     * These are the types that produce or modify code/contracts.
     */
    static final Set<WorkerType> DOMAIN_WORKER_TYPES = Set.of(
            WorkerType.BE, WorkerType.FE, WorkerType.AI_TASK,
            WorkerType.CONTRACT, WorkerType.DBA, WorkerType.MOBILE
    );

    /**
     * Worker types that receive TOOL_MANAGER analysis (#24 L2).
     * Superset of {@link #DOMAIN_WORKER_TYPES} — also includes REVIEW.
     */
    static final Set<WorkerType> TOOL_MANAGER_TARGET_TYPES = Set.of(
            WorkerType.BE, WorkerType.FE, WorkerType.AI_TASK,
            WorkerType.CONTRACT, WorkerType.DBA, WorkerType.MOBILE,
            WorkerType.REVIEW
    );

    private final EnrichmentProperties properties;

    public EnrichmentInjectorService(EnrichmentProperties properties) {
        this.properties = properties;
    }

    /**
     * Injects enrichment tasks into the plan if they are not already present.
     *
     * <p>Modifies the plan in-place: adds new PlanItems and wires them as
     * dependencies of all domain workers (BE, FE, AI_TASK, CONTRACT, DBA, MOBILE).
     * When {@code includeToolManager} is enabled, also injects per-task TM-* items
     * that produce fine-grained HookPolicies for each target worker.</p>
     *
     * @param plan the plan to enrich (post-decompose, pre-persist)
     */
    public void inject(Plan plan) {
        if (!properties.autoInject()) {
            return;
        }

        List<PlanItem> items = plan.getItems();
        if (items.isEmpty()) {
            return;
        }

        boolean hasCm = hasWorkerType(items, WorkerType.CONTEXT_MANAGER);
        boolean hasRm = hasWorkerType(items, WorkerType.RAG_MANAGER);
        boolean hasSm = hasWorkerType(items, WorkerType.SCHEMA_MANAGER);

        // Collect task keys of injected enrichment items (for dependency wiring)
        List<String> injectedKeys = new ArrayList<>();

        // CM-001: always first, no dependencies
        String cmKey = null;
        if (properties.includeContextManager() && !hasCm) {
            cmKey = "CM-001";
            PlanItem cmItem = new PlanItem(
                    UUID.randomUUID(), 0, cmKey,
                    "Explore codebase and identify relevant context",
                    "Explore the codebase to identify relevant files, world state, "
                            + "key constraints, and missing information for downstream workers. "
                            + "Plan spec: " + truncate(plan.getSpec(), 500),
                    WorkerType.CONTEXT_MANAGER,
                    null,
                    List.of(),
                    List.of()
            );
            plan.addItem(cmItem);
            injectedKeys.add(cmKey);
        } else if (hasCm) {
            // Find existing CM key for RM/SM/TM dependency wiring
            cmKey = findTaskKeyByType(items, WorkerType.CONTEXT_MANAGER);
        }

        // RM-001: depends on CM (if present)
        String rmKey = null;
        if (properties.includeRag() && !hasRm) {
            rmKey = "RM-001";
            List<String> rmDeps = cmKey != null ? List.of(cmKey) : List.of();
            PlanItem rmItem = new PlanItem(
                    UUID.randomUUID(), 1, rmKey,
                    "Semantic search on vectorDB and graphDB",
                    "Perform semantic search on the vector store (pgvector) and graph database "
                            + "(Apache AGE) to find relevant code fragments, structural insights, "
                            + "and related files for downstream workers.",
                    WorkerType.RAG_MANAGER,
                    null,
                    rmDeps,
                    List.of()
            );
            plan.addItem(rmItem);
            injectedKeys.add(rmKey);
        } else if (hasRm) {
            rmKey = findTaskKeyByType(items, WorkerType.RAG_MANAGER);
        }

        // SM-001: depends on CM (if present)
        if (properties.includeSchema() && !hasSm) {
            List<String> smDeps = cmKey != null ? List.of(cmKey) : List.of();
            PlanItem smItem = new PlanItem(
                    UUID.randomUUID(), 1, "SM-001",
                    "Extract API interfaces and architectural contracts",
                    "Extract API interfaces, DTOs, and architectural constraints "
                            + "relevant to the downstream tasks.",
                    WorkerType.SCHEMA_MANAGER,
                    null,
                    smDeps,
                    List.of()
            );
            plan.addItem(smItem);
            injectedKeys.add("SM-001");
        }

        if (!injectedKeys.isEmpty()) {
            // Wire injected enrichment keys as dependencies of all domain workers
            int wiredCount = 0;
            for (PlanItem item : items) {
                if (DOMAIN_WORKER_TYPES.contains(item.getWorkerType())) {
                    for (String key : injectedKeys) {
                        item.addDependency(key);
                    }
                    wiredCount++;
                }
            }

            log.info("Enrichment injected into plan {}: {} tasks added ({}), "
                            + "{} domain workers wired as dependents",
                    plan.getId(), injectedKeys.size(), injectedKeys, wiredCount);
        }

        // TM-*: per-task tool policy refinement (#24 L2)
        // One TOOL_MANAGER task per target worker, depends on CM + RM
        if (properties.includeToolManager()) {
            injectToolManagerTasks(plan, cmKey, rmKey);
        }
    }

    /**
     * Injects one TOOL_MANAGER task per domain/review worker in the plan.
     *
     * <p>Each TM-* task depends on CM and RM (to access their enrichment results)
     * and its target domain task is wired to depend on the corresponding TM-* task.
     * This ensures the per-task HookPolicy is computed before the target dispatches.</p>
     *
     * @param plan  the plan being enriched
     * @param cmKey the CONTEXT_MANAGER task key (null if CM not present)
     * @param rmKey the RAG_MANAGER task key (null if RM not present)
     */
    private void injectToolManagerTasks(Plan plan, String cmKey, String rmKey) {
        // Snapshot the current items to avoid ConcurrentModificationException
        List<PlanItem> snapshot = new ArrayList<>(plan.getItems());
        int tmCount = 0;

        for (PlanItem targetItem : snapshot) {
            if (!TOOL_MANAGER_TARGET_TYPES.contains(targetItem.getWorkerType())) {
                continue;
            }

            String tmKey = "TM-" + targetItem.getTaskKey();

            // TM depends on CM + RM to read their outputs as dependency results
            List<String> tmDeps = new ArrayList<>();
            if (cmKey != null) tmDeps.add(cmKey);
            if (rmKey != null) tmDeps.add(rmKey);

            PlanItem tmItem = new PlanItem(
                    UUID.randomUUID(), 1, tmKey,
                    "Analyze tool requirements for " + targetItem.getTaskKey(),
                    buildTmDescription(targetItem),
                    WorkerType.TOOL_MANAGER,
                    null,
                    tmDeps,
                    List.of()
            );
            plan.addItem(tmItem);

            // Target domain task depends on its TM task
            targetItem.addDependency(tmKey);
            tmCount++;
        }

        if (tmCount > 0) {
            log.info("Tool Manager injected into plan {}: {} TM-* tasks added for domain workers",
                    plan.getId(), tmCount);
        }
    }

    private static String buildTmDescription(PlanItem target) {
        return String.format(
                "Analyze task %s (%s / %s) and produce a HookPolicy JSON.%n"
                        + "Target task key: %s%n"
                        + "Target description: %s%n"
                        + "Read dependency results from CONTEXT_MANAGER and RAG_MANAGER "
                        + "to determine relevant files and path ownership.",
                target.getTaskKey(), target.getWorkerType(),
                target.getWorkerProfile() != null ? target.getWorkerProfile() : "default",
                target.getTaskKey(),
                truncate(target.getDescription(), 300)
        );
    }

    private boolean hasWorkerType(List<PlanItem> items, WorkerType type) {
        return items.stream().anyMatch(i -> i.getWorkerType() == type);
    }

    private String findTaskKeyByType(List<PlanItem> items, WorkerType type) {
        return items.stream()
                .filter(i -> i.getWorkerType() == type)
                .map(PlanItem::getTaskKey)
                .findFirst()
                .orElse(null);
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}
