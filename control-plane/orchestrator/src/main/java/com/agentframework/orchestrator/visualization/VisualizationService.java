package com.agentframework.orchestrator.visualization;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.TropicalScheduler;
import com.agentframework.orchestrator.lifecycle.ProjectRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates plan visualization: load → build model → layout → render.
 *
 * <p>Caches rendered output in a {@link ConcurrentHashMap} with TTL-based
 * eviction. Supports single-plan, hierarchical, and project-level views.</p>
 */
@Service
@ConditionalOnProperty(prefix = "visualization", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(VisualizationConfig.class)
public class VisualizationService {

    private static final Logger log = LoggerFactory.getLogger(VisualizationService.class);

    private final VisualizationConfig config;
    @Nullable private final PlanRepository planRepository;
    @Nullable private final CriticalPathCalculator criticalPathCalculator;
    @Nullable private final ProjectRepository projectRepository;
    private final JdbcTemplate jdbc;
    private final GraphModelBuilder modelBuilder;
    private final GraphLayoutEngine layoutEngine;
    private final MermaidRenderer mermaidRenderer;
    private final D3JsonRenderer d3JsonRenderer;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    /** Cache: key → (rendered output, expiry instant) */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public VisualizationService(VisualizationConfig config,
                                 @Nullable PlanRepository planRepository,
                                 @Nullable CriticalPathCalculator criticalPathCalculator,
                                 @Nullable ProjectRepository projectRepository,
                                 JdbcTemplate jdbc,
                                 @Nullable MeterRegistry meterRegistry) {
        this.config = config;
        this.planRepository = planRepository;
        this.criticalPathCalculator = criticalPathCalculator;
        this.projectRepository = projectRepository;
        this.jdbc = jdbc;
        this.modelBuilder = new GraphModelBuilder();
        this.layoutEngine = new GraphLayoutEngine(
                config.layout().horizontalSpacing(),
                config.layout().verticalSpacing());
        this.mermaidRenderer = new MermaidRenderer();
        this.d3JsonRenderer = new D3JsonRenderer();

        if (meterRegistry != null) {
            cacheHits = Counter.builder("visualization_cache_hits_total")
                    .description("Visualization cache hits").register(meterRegistry);
            cacheMisses = Counter.builder("visualization_cache_misses_total")
                    .description("Visualization cache misses").register(meterRegistry);
        } else {
            cacheHits = null;
            cacheMisses = null;
        }
    }

    /**
     * Renders a single plan visualization.
     *
     * @param planId plan UUID
     * @param format "mermaid" or "d3json"
     * @return rendered output, or null if plan not found
     */
    @Nullable
    public String renderPlan(UUID planId, String format) {
        String cacheKey = "plan:" + planId + ":" + format;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiry.isAfter(Instant.now())) {
            if (cacheHits != null) cacheHits.increment();
            return cached.value;
        }

        if (planRepository == null) return null;
        Plan plan = planRepository.findById(planId).orElse(null);
        if (plan == null) return null;

        if (cacheMisses != null) cacheMisses.increment();

        Set<String> criticalPath = computeCriticalPath(plan);
        PlanGraphModel model = modelBuilder.buildSinglePlan(plan, criticalPath);
        PlanGraphModel layoutModel = layoutEngine.applyLayout(model);

        String result = renderToFormat(layoutModel, format);
        cacheResult(cacheKey, result);
        return result;
    }

    /**
     * Renders a plan with its sub-plans (hierarchical view).
     *
     * @param planId root plan UUID
     * @param format "mermaid" or "d3json"
     * @return rendered output, or null if plan not found
     */
    @Nullable
    public String renderHierarchy(UUID planId, String format) {
        String cacheKey = "hierarchy:" + planId + ":" + format;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiry.isAfter(Instant.now())) {
            if (cacheHits != null) cacheHits.increment();
            return cached.value;
        }

        if (planRepository == null) return null;
        Plan rootPlan = planRepository.findById(planId).orElse(null);
        if (rootPlan == null) return null;

        if (cacheMisses != null) cacheMisses.increment();

        // Find sub-plans via JDBC (PlanRepository doesn't have findByParentPlanId)
        List<Plan> subPlans = findSubPlans(planId);

        Map<UUID, Set<String>> criticalPaths = new HashMap<>();
        criticalPaths.put(planId, computeCriticalPath(rootPlan));
        for (Plan sub : subPlans) {
            criticalPaths.put(sub.getId(), computeCriticalPath(sub));
        }

        PlanGraphModel model = modelBuilder.buildHierarchy(rootPlan, subPlans, criticalPaths);
        PlanGraphModel layoutModel = layoutEngine.applyLayout(model);

        String result = renderToFormat(layoutModel, format);
        cacheResult(cacheKey, result);
        return result;
    }

    /**
     * Renders a project-level visualization (all plans in a project).
     *
     * @param projectId project UUID
     * @param format    "mermaid" or "d3json"
     * @return rendered output, or null if project not found
     */
    @Nullable
    public String renderProject(UUID projectId, String format) {
        String cacheKey = "project:" + projectId + ":" + format;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiry.isAfter(Instant.now())) {
            if (cacheHits != null) cacheHits.increment();
            return cached.value;
        }

        if (projectRepository == null || planRepository == null) return null;
        if (projectRepository.findById(projectId).isEmpty()) return null;

        if (cacheMisses != null) cacheMisses.increment();

        // Get plan IDs linked to this project
        List<Map<String, Object>> projectPlans = jdbc.queryForList("""
            SELECT plan_id, epic_name, ordinal FROM project_plans
            WHERE project_id = ?::uuid ORDER BY ordinal
            """, projectId.toString());

        if (projectPlans.isEmpty()) return null;

        // Load each plan and build combined model
        List<PlanGraphModel.GraphNode> allNodes = new ArrayList<>();
        List<PlanGraphModel.GraphEdge> allEdges = new ArrayList<>();

        for (Map<String, Object> pp : projectPlans) {
            UUID planId = UUID.fromString(pp.get("plan_id").toString());
            String epicName = pp.get("epic_name") != null ? pp.get("epic_name").toString() : "Plan";

            Plan plan = planRepository.findById(planId).orElse(null);
            if (plan == null) continue;

            // Add epic node
            allNodes.add(new PlanGraphModel.GraphNode(
                    "epic:" + planId, epicName,
                    PlanGraphModel.NodeType.EPIC,
                    plan.getStatus().name(),
                    "epic", 0, 0, 0,
                    Map.of("ordinal", ((Number) pp.get("ordinal")).intValue())));

            // Build plan model
            Set<String> cp = computeCriticalPath(plan);
            PlanGraphModel planModel = modelBuilder.buildSinglePlan(plan, cp);

            // Prefix node IDs with plan UUID
            String prefix = planId.toString().substring(0, 8) + "/";
            for (PlanGraphModel.GraphNode node : planModel.nodes()) {
                allNodes.add(new PlanGraphModel.GraphNode(
                        prefix + node.id(), node.label(), node.type(), node.status(),
                        node.group(), node.layer(), node.x(), node.y(), node.metadata()));
            }
            for (PlanGraphModel.GraphEdge edge : planModel.edges()) {
                allEdges.add(new PlanGraphModel.GraphEdge(
                        prefix + edge.source(), prefix + edge.target(),
                        edge.type(), edge.onCriticalPath()));
            }

            // Connect epic to source items
            for (PlanGraphModel.GraphNode node : planModel.nodes()) {
                boolean hasIncoming = planModel.edges().stream()
                        .anyMatch(e -> e.target().equals(node.id()));
                if (!hasIncoming) {
                    allEdges.add(new PlanGraphModel.GraphEdge(
                            "epic:" + planId, prefix + node.id(),
                            PlanGraphModel.EdgeType.PARENT_CHILD, false));
                }
            }
        }

        PlanGraphModel combined = new PlanGraphModel(
                projectId, "PROJECT", allNodes, allEdges,
                Map.of("planCount", projectPlans.size(), "totalNodes", allNodes.size()));

        PlanGraphModel layoutModel = layoutEngine.applyLayout(combined);
        String result = renderToFormat(layoutModel, format);
        cacheResult(cacheKey, result);
        return result;
    }

    /**
     * Evicts expired cache entries. Runs every 60 seconds.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredCache() {
        Instant now = Instant.now();
        int evicted = 0;
        for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().expiry.isBefore(now)) {
                it.remove();
                evicted++;
            }
        }
        if (evicted > 0) {
            log.debug("Visualization cache eviction: removed {} expired entries", evicted);
        }

        // Max entries cap
        if (cache.size() > config.cache().maxEntries()) {
            int toRemove = cache.size() - config.cache().maxEntries();
            var entries = cache.entrySet().iterator();
            for (int i = 0; i < toRemove && entries.hasNext(); i++) {
                entries.next();
                entries.remove();
            }
        }
    }

    // --- Internal ---

    private Set<String> computeCriticalPath(Plan plan) {
        if (criticalPathCalculator == null || plan.getItems().isEmpty()) return Set.of();
        try {
            TropicalScheduler.ScheduleResult result = criticalPathCalculator.computeSchedule(plan);
            return new HashSet<>(result.criticalPath());
        } catch (Exception e) {
            log.debug("Critical path computation failed for plan {}: {}", plan.getId(), e.getMessage());
            return Set.of();
        }
    }

    private List<Plan> findSubPlans(UUID parentPlanId) {
        if (planRepository == null) return List.of();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id FROM plans WHERE parent_plan_id = ?::uuid", parentPlanId.toString());
            List<Plan> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                UUID subId = UUID.fromString(row.get("id").toString());
                planRepository.findById(subId).ifPresent(result::add);
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to find sub-plans for {}: {}", parentPlanId, e.getMessage());
            return List.of();
        }
    }

    private String renderToFormat(PlanGraphModel model, String format) {
        return "mermaid".equalsIgnoreCase(format)
                ? mermaidRenderer.render(model)
                : d3JsonRenderer.render(model);
    }

    private void cacheResult(String key, String value) {
        Instant expiry = Instant.now().plusSeconds(config.cache().ttlSeconds());
        cache.put(key, new CacheEntry(value, expiry));
    }

    private record CacheEntry(String value, Instant expiry) {}
}
