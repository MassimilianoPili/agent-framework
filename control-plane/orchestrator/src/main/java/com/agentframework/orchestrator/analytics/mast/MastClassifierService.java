package com.agentframework.orchestrator.analytics.mast;

import com.agentframework.orchestrator.analytics.mast.MastTaxonomy.FailureClassification;
import com.agentframework.orchestrator.analytics.selfrefine.FlipRateMonitor;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Classifier that maps failed tasks to MAST failure modes.
 *
 * <p>Runs all detectors in priority order and returns the highest-confidence
 * classification. When multiple failure modes are detected, the one with the
 * highest confidence wins — with inter-agent failures (FC2) taking priority
 * over emergent (FC3) at equal confidence, since FC2 has more targeted
 * recovery strategies.</p>
 *
 * <h3>Detection pipeline</h3>
 * <ol>
 *   <li>Run all 14 detectors from {@link MastDetectors}</li>
 *   <li>Sort by confidence (descending), then by category priority (FC2 > FC1 > FC3)</li>
 *   <li>Return the top classification, or FM2_INCOMPLETE_REQ as fallback</li>
 * </ol>
 *
 * @see MastDetectors
 * @see MastTaxonomy
 * @see SelfHealingRouter
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.mast", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(MastConfig.class)
public class MastClassifierService {

    private static final Logger log = LoggerFactory.getLogger(MastClassifierService.class);

    private final FlipRateMonitor flipRateMonitor;
    private final MastConfig config;

    public MastClassifierService(FlipRateMonitor flipRateMonitor,
                                  MastConfig config) {
        this.flipRateMonitor = flipRateMonitor;
        this.config = config;
    }

    /**
     * Classifies a failed task into a MAST failure mode.
     *
     * @param failedItem the failed plan item
     * @param plan       the plan context (for plan-level detectors)
     * @return the highest-confidence failure classification
     */
    public FailureClassification classify(PlanItem failedItem, Plan plan) {
        List<FailureClassification> candidates = new ArrayList<>();

        // FC1: Specification Failures
        MastDetectors.detectAmbiguousSpec(failedItem, plan, config.minSpecLength())
                .ifPresent(candidates::add);
        MastDetectors.detectIncompleteRequirements(failedItem)
                .ifPresent(candidates::add);

        // FC2: Inter-Agent Failures
        MastDetectors.detectCommunicationBreakdown(failedItem)
                .ifPresent(candidates::add);
        MastDetectors.detectCoordinationDeadlock(plan)
                .ifPresent(candidates::add);
        MastDetectors.detectResourceContention(failedItem)
                .ifPresent(candidates::add);
        MastDetectors.detectProtocolViolation(failedItem)
                .ifPresent(candidates::add);
        MastDetectors.detectTrustDegradation(failedItem, config.eloCollapseThreshold())
                .ifPresent(candidates::add);

        // FC3: Emergent Failures
        MastDetectors.detectCascadingFailure(plan, failedItem, config.cascadeDepth())
                .ifPresent(candidates::add);

        double flipRate = flipRateMonitor.getFlipRate(failedItem.getTaskKey());
        MastDetectors.detectOscillation(failedItem, flipRate, config.oscillationWindow())
                .ifPresent(candidates::add);

        MastDetectors.detectPartialCompletion(plan, config.partialCompletionThreshold())
                .ifPresent(candidates::add);
        MastDetectors.detectRecoveryFailure(failedItem)
                .ifPresent(candidates::add);

        // Select highest-confidence classification
        Optional<FailureClassification> best = candidates.stream()
                .max(Comparator.<FailureClassification>comparingDouble(c -> c.confidence())
                        .thenComparingInt(c -> categoryPriority(c.category())));

        FailureClassification result = best.orElseGet(() ->
                new FailureClassification(
                        MastTaxonomy.FailureMode.FM2_INCOMPLETE_REQ, 0.3,
                        "no specific failure mode detected — defaulting to incomplete requirements"));

        log.info("MAST classification: task={} mode={} category={} confidence={} evidence={}",
                failedItem.getTaskKey(),
                result.mode(), result.category(),
                String.format("%.2f", result.confidence()),
                result.evidence());

        return result;
    }

    /**
     * Classifies all failed items in a plan.
     *
     * @param plan the plan with failed items
     * @return list of classifications for all failed items
     */
    public List<ClassifiedFailure> classifyAll(Plan plan) {
        return plan.getItems().stream()
                .filter(item -> item.getStatus() == com.agentframework.orchestrator.domain.ItemStatus.FAILED)
                .map(item -> new ClassifiedFailure(item, classify(item, plan)))
                .toList();
    }

    /**
     * A failed item with its MAST classification.
     */
    public record ClassifiedFailure(PlanItem item, FailureClassification classification) {}

    /**
     * Category priority for tie-breaking (higher = preferred).
     * FC2 (inter-agent) is preferred because it has the most targeted recovery strategies.
     */
    private int categoryPriority(MastTaxonomy.FailureCategory category) {
        return switch (category) {
            case FC2_INTER_AGENT -> 3;
            case FC1_SPECIFICATION -> 2;
            case FC3_EMERGENT -> 1;
        };
    }
}
