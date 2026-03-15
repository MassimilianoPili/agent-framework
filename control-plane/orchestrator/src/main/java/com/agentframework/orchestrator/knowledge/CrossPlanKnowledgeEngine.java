package com.agentframework.orchestrator.knowledge;

import com.agentframework.orchestrator.analytics.metalearning.PlanArchetypeRegistry;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.knowledge.ArchDecisionCache.ArchDecision;
import com.agentframework.orchestrator.knowledge.ErrorPatternLibrary.ErrorPattern;
import com.agentframework.orchestrator.knowledge.InsightExtractor.PlanInsight;
import com.agentframework.orchestrator.knowledge.PromptRefinementStore.RankedPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-Plan Knowledge Transfer Engine.
 *
 * <p>Orchestrates 3 knowledge transfer channels to enable learning across plans:</p>
 * <ol>
 *   <li><b>Prompt Refinements</b>: reuse high-scoring prompt variants for similar archetypes</li>
 *   <li><b>Error Patterns</b> (Reflexion): retrieve relevant error→correction pairs</li>
 *   <li><b>Architectural Decisions</b>: leverage Council decisions with known outcomes</li>
 * </ol>
 *
 * <p>Additionally extracts generalizable insights (ExpeL pattern) from completed
 * plans for future reuse.</p>
 *
 * <p>Transfer confidence is estimated using archetype similarity and historical
 * success rates (CoPS-inspired, Yang 2024), avoiding the O(n³) cost of full GP
 * posterior computation.</p>
 *
 * @see <a href="https://arxiv.org/abs/2310.10012">Reflexion (Shinn et al., NeurIPS 2023)</a>
 * @see <a href="https://arxiv.org/abs/2308.10144">ExpeL (Zhao et al., AAAI 2024)</a>
 * @see <a href="https://arxiv.org/abs/2402.02024">CoPS (Yang et al., 2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "cross-plan-knowledge", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(CrossPlanKnowledgeConfig.class)
public class CrossPlanKnowledgeEngine {

    private static final Logger log = LoggerFactory.getLogger(CrossPlanKnowledgeEngine.class);

    private final ErrorPatternLibrary errorPatterns;
    private final PromptRefinementStore promptStore;
    private final ArchDecisionCache archDecisions;
    private final InsightExtractor insightExtractor;
    private final CrossPlanKnowledgeConfig config;
    @Nullable private final PlanArchetypeRegistry archetypeRegistry;

    public CrossPlanKnowledgeEngine(ErrorPatternLibrary errorPatterns,
                                     PromptRefinementStore promptStore,
                                     ArchDecisionCache archDecisions,
                                     InsightExtractor insightExtractor,
                                     CrossPlanKnowledgeConfig config,
                                     @Nullable PlanArchetypeRegistry archetypeRegistry) {
        this.errorPatterns = errorPatterns;
        this.promptStore = promptStore;
        this.archDecisions = archDecisions;
        this.insightExtractor = insightExtractor;
        this.config = config;
        this.archetypeRegistry = archetypeRegistry;
    }

    /**
     * Retrieves all relevant knowledge for a new plan specification.
     *
     * <p>Queries all 3 channels and combines results into a single knowledge
     * bundle that can be injected into the planner prompt.</p>
     *
     * @param spec            plan specification text
     * @param taskEmbedding   embedding of the specification (1024 dim, nullable)
     * @param workerType      target worker type (for prompt retrieval)
     * @return knowledge bundle with all channels
     */
    public KnowledgeBundle retrieveKnowledge(String spec, @Nullable float[] taskEmbedding,
                                               @Nullable String workerType) {
        // Determine archetype match
        String archetype = findMatchingArchetype(spec);

        // Channel 1: Best prompt for this archetype + worker type
        Optional<RankedPrompt> bestPrompt = (archetype != null && workerType != null)
                ? promptStore.getBestPrompt(archetype, workerType)
                : Optional.empty();

        // Channel 2: Relevant error patterns
        List<ErrorPattern> relevantErrors = (taskEmbedding != null)
                ? errorPatterns.findRelevant(taskEmbedding, 0)
                : List.of();

        // Channel 3: Relevant architectural decisions
        List<ArchDecision> decisions = archDecisions.retrieveDecisions(spec, 5);

        // Bonus: relevant insights
        List<PlanInsight> insights = insightExtractor.findRelevant(null, 5);

        // Estimate transfer confidence
        double confidence = estimateTransferConfidence(archetype, relevantErrors, decisions);

        log.debug("Knowledge retrieval for '{}': archetype={}, prompt={}, errors={}, decisions={}, insights={}, confidence={}",
                truncate(spec, 50), archetype,
                bestPrompt.isPresent() ? "found" : "none",
                relevantErrors.size(), decisions.size(), insights.size(), confidence);

        return new KnowledgeBundle(archetype, bestPrompt.orElse(null),
                relevantErrors, decisions, insights, confidence);
    }

    /**
     * Records knowledge from a completed plan execution.
     *
     * <p>Called after plan completion to update all knowledge channels:</p>
     * <ul>
     *   <li>Extract insights (ExpeL pattern)</li>
     *   <li>Register archetype (if not already registered)</li>
     * </ul>
     *
     * @param plan the completed plan
     */
    public void recordPlanKnowledge(Plan plan) {
        try {
            // Extract insights
            List<PlanInsight> insights = insightExtractor.extractInsights(plan);
            log.debug("Extracted {} insights from plan {}", insights.size(), plan.getId());

            // Register archetype via existing registry
            if (archetypeRegistry != null) {
                boolean anyFailed = plan.getItems() != null && plan.getItems().stream()
                        .anyMatch(i -> "FAILED".equals(i.getStatus()));
                if (anyFailed) {
                    archetypeRegistry.registerFailedArchetype(plan);
                } else {
                    archetypeRegistry.registerArchetype(plan);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to record plan knowledge: {}", e.getMessage());
        }
    }

    /**
     * Records a prompt outcome for the refinement channel.
     */
    public void recordPromptOutcome(String archetype, String workerType,
                                      String promptVariant, double prmScore) {
        promptStore.recordOutcome(archetype, workerType, promptVariant, prmScore);
    }

    /**
     * Records an error pattern for the error library channel.
     */
    public void recordError(ErrorPattern pattern) {
        errorPatterns.record(pattern);
    }

    /**
     * Caches an architectural decision.
     */
    public void cacheDecision(ArchDecision decision) {
        archDecisions.cacheDecision(decision);
    }

    /**
     * Formats the knowledge bundle as text for planner prompt injection.
     */
    public String formatForPlanner(KnowledgeBundle bundle) {
        if (bundle.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## Cross-Plan Knowledge\n\n");

        if (bundle.bestPrompt() != null) {
            sb.append("### Recommended Prompt (score=")
                    .append(String.format("%.2f", bundle.bestPrompt().prmScoreAvg()))
                    .append(", used ").append(bundle.bestPrompt().usageCount()).append("x)\n")
                    .append(bundle.bestPrompt().promptVariant()).append("\n\n");
        }

        if (!bundle.errorPatterns().isEmpty()) {
            sb.append("### Known Error Patterns\n");
            for (ErrorPattern ep : bundle.errorPatterns()) {
                sb.append("- **Error**: ").append(ep.errorDescription())
                        .append(" → **Fix**: ").append(ep.correction())
                        .append(" (confidence=").append(String.format("%.2f", ep.confidence())).append(")\n");
            }
            sb.append('\n');
        }

        if (!bundle.archDecisions().isEmpty()) {
            sb.append("### Architectural Precedents\n");
            for (ArchDecision ad : bundle.archDecisions()) {
                sb.append("- **Context**: ").append(ad.context())
                        .append(" → **Decision**: ").append(ad.decision())
                        .append(" (outcome=").append(String.format("%.2f", ad.outcomeScore())).append(")\n");
            }
            sb.append('\n');
        }

        if (!bundle.insights().isEmpty()) {
            sb.append("### Insights from Similar Plans\n");
            for (PlanInsight pi : bundle.insights()) {
                sb.append("- [").append(pi.category()).append("] ").append(pi.insightText()).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Returns engine status for monitoring endpoints.
     */
    public Map<String, Object> status() {
        return Map.of(
                "errorPatterns", errorPatterns.stats(),
                "promptRefinements", promptStore.stats(),
                "archDecisions", archDecisions.stats());
    }

    // --- Private helpers ---

    @Nullable
    private String findMatchingArchetype(String spec) {
        if (archetypeRegistry == null || spec == null) return null;
        try {
            var matches = archetypeRegistry.findSimilar(spec, 1);
            if (!matches.isEmpty()) {
                return matches.getFirst().toString(); // archetype ID
            }
        } catch (Exception e) {
            log.debug("Archetype matching failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Estimates transfer confidence based on available knowledge.
     *
     * <p>CoPS-inspired: confidence is proportional to the quality and quantity
     * of available knowledge from similar contexts.</p>
     */
    private double estimateTransferConfidence(@Nullable String archetype,
                                                List<ErrorPattern> errors,
                                                List<ArchDecision> decisions) {
        double confidence = 0.0;

        // Archetype match contributes 0.3
        if (archetype != null) confidence += 0.3;

        // Error patterns contribute up to 0.3 (based on avg confidence)
        if (!errors.isEmpty()) {
            double avgErrorConfidence = errors.stream()
                    .mapToDouble(ErrorPattern::confidence).average().orElse(0);
            confidence += 0.3 * avgErrorConfidence;
        }

        // Architectural decisions contribute up to 0.4 (based on avg outcome)
        if (!decisions.isEmpty()) {
            double avgOutcome = decisions.stream()
                    .mapToDouble(ArchDecision::outcomeScore).average().orElse(0);
            confidence += 0.4 * avgOutcome;
        }

        return Math.min(confidence, 1.0);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // --- Public types ---

    /**
     * Bundle of all knowledge retrieved for a new plan.
     *
     * @param archetype      matched archetype identifier (nullable)
     * @param bestPrompt     best prompt variant for this archetype (nullable)
     * @param errorPatterns  relevant error patterns
     * @param archDecisions  relevant architectural decisions
     * @param insights       generalizable insights from similar plans
     * @param transferConfidence estimated confidence [0.0, 1.0]
     */
    public record KnowledgeBundle(
            @Nullable String archetype,
            @Nullable RankedPrompt bestPrompt,
            List<ErrorPattern> errorPatterns,
            List<ArchDecision> archDecisions,
            List<PlanInsight> insights,
            double transferConfidence
    ) {
        public boolean isEmpty() {
            return bestPrompt == null && errorPatterns.isEmpty()
                    && archDecisions.isEmpty() && insights.isEmpty();
        }
    }
}
