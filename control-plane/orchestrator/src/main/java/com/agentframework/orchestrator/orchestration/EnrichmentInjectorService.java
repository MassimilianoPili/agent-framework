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
 * RAG_MANAGER, and optionally SCHEMA_MANAGER tasks as dependencies of domain workers.
 *
 * <p>Called after {@link com.agentframework.orchestrator.planner.PlannerService#decompose(Plan)}
 * in {@link OrchestrationService#createAndStart(String, com.agentframework.orchestrator.api.PlanRequest.Budget)}.
 * Operates in-memory on the Plan before it is persisted.</p>
 *
 * <p><b>Idempotent</b>: if the planner already generated enrichment tasks (Level 1),
 * they are not duplicated. The check is based on {@link PlanItem#getWorkerType()}.</p>
 *
 * <p><b>Ordinals</b>: enrichment items use ordinal 0 (CM) and 1 (RM, SM). Existing
 * items keep their original ordinals. Dispatch order is controlled by the dependency
 * DAG ({@code findDispatchableItems}), not by ordinals — ordinals are cosmetic only.</p>
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

    private final EnrichmentProperties properties;

    public EnrichmentInjectorService(EnrichmentProperties properties) {
        this.properties = properties;
    }

    /**
     * Injects enrichment tasks into the plan if they are not already present.
     *
     * <p>Modifies the plan in-place: adds new PlanItems and wires them as
     * dependencies of all domain workers (BE, FE, AI_TASK, CONTRACT, DBA, MOBILE).</p>
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
                    List.of()
            );
            plan.addItem(cmItem);
            injectedKeys.add(cmKey);
        } else if (hasCm) {
            // Find existing CM key for RM/SM dependency wiring
            cmKey = findTaskKeyByType(items, WorkerType.CONTEXT_MANAGER);
        }

        // RM-001: depends on CM (if present)
        if (properties.includeRag() && !hasRm) {
            List<String> rmDeps = cmKey != null ? List.of(cmKey) : List.of();
            PlanItem rmItem = new PlanItem(
                    UUID.randomUUID(), 1, "RM-001",
                    "Semantic search on vectorDB and graphDB",
                    "Perform semantic search on the vector store (pgvector) and graph database "
                            + "(Apache AGE) to find relevant code fragments, structural insights, "
                            + "and related files for downstream workers.",
                    WorkerType.RAG_MANAGER,
                    null,
                    rmDeps
            );
            plan.addItem(rmItem);
            injectedKeys.add("RM-001");
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
                    smDeps
            );
            plan.addItem(smItem);
            injectedKeys.add("SM-001");
        }

        if (injectedKeys.isEmpty()) {
            log.debug("No enrichment tasks injected for plan {} (already present or disabled)",
                    plan.getId());
            return;
        }

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
