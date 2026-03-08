package com.agentframework.orchestrator.gp;

import com.agentframework.gp.engine.GaussianProcessEngine;
import com.agentframework.gp.math.RbfKernel;
import com.agentframework.gp.model.GpPosterior;
import com.agentframework.gp.model.GpPrediction;
import com.agentframework.gp.model.TrainingPoint;
import com.agentframework.orchestrator.domain.PlanOutcome;
import com.agentframework.orchestrator.repository.PlanOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gaussian Process predictor for plan decomposition quality.
 *
 * <p>Learns from past plan outcomes to predict the expected reward of a proposed
 * plan structure, enriching {@link com.agentframework.orchestrator.council.CouncilReport}
 * with data-driven quality estimates.
 *
 * <h3>Feature vector (5 dimensions)</h3>
 * <ol>
 *   <li>{@code nTasks}        — total task count (proxy for plan complexity)</li>
 *   <li>{@code hasContextTask} — 1 if a CONTEXT_MANAGER task is present, 0 otherwise</li>
 *   <li>{@code hasReviewTask}  — 1 if a REVIEW task is present, 0 otherwise</li>
 *   <li>{@code nBeTasks}      — number of BE worker tasks</li>
 *   <li>{@code nFeTasks}      — number of FE worker tasks</li>
 * </ol>
 *
 * <p>The GP engine is instantiated locally (not the shared {@code gp.enabled} bean)
 * so this feature is independent of worker-selection GP configuration.
 * Enabled via {@code council.taste-profile.enabled=true} (default: true).</p>
 */
@Service
@ConditionalOnProperty(prefix = "council.taste-profile", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class PlanDecompositionPredictor {

    private static final Logger log = LoggerFactory.getLogger(PlanDecompositionPredictor.class);

    static final int MIN_TRAINING_POINTS = 5;

    @Value("${council.taste-profile.max-training-points:200}")
    int maxTrainingPoints;

    @Value("${council.taste-profile.noise-variance:0.1}")
    double noiseVariance;

    @Value("${council.taste-profile.length-scale:2.0}")
    double lengthScale;

    private final PlanOutcomeRepository repository;
    private final TaskOutcomeRepository taskOutcomeRepository;

    public PlanDecompositionPredictor(PlanOutcomeRepository repository,
                                      TaskOutcomeRepository taskOutcomeRepository) {
        this.repository = repository;
        this.taskOutcomeRepository = taskOutcomeRepository;
    }

    /**
     * Predicts the expected reward for a proposed plan structure.
     *
     * @return GP prediction (mu, sigma2), or {@link Optional#empty()} on cold start
     *         (fewer than {@value #MIN_TRAINING_POINTS} training points) or Cholesky failure.
     */
    public Optional<GpPrediction> predict(int nTasks, boolean hasContext,
                                          boolean hasReview, int nBe, int nFe) {
        List<PlanOutcome> data = repository.findTrainingData(PageRequest.of(0, maxTrainingPoints));

        if (data.size() < MIN_TRAINING_POINTS) {
            log.debug("PlanDecompositionPredictor: cold start ({} < {} training points)",
                      data.size(), MIN_TRAINING_POINTS);
            return Optional.empty();
        }

        List<TrainingPoint> training = data.stream()
                .map(o -> new TrainingPoint(
                        toFeatureVector(o.getNTasks(), o.isHasContextTask(),
                                        o.isHasReviewTask(), o.getNBeTasks(), o.getNFeTasks()),
                        o.getActualReward(),
                        "plan-structure"))
                .toList();

        GaussianProcessEngine engine = buildEngine();
        GpPosterior posterior = engine.fit(training);
        if (posterior == null) {
            log.warn("PlanDecompositionPredictor: GP fit failed (Cholesky) on {} points", data.size());
            return Optional.empty();
        }

        float[] query = toFeatureVector(nTasks, hasContext, hasReview, nBe, nFe);
        GpPrediction prediction = engine.predict(posterior, query);
        log.debug("PlanDecompositionPredictor: nTasks={} → mu={:.3f}, sigma2={:.4f}",
                  nTasks, prediction.mu(), prediction.sigma2());
        return Optional.of(prediction);
    }

    /**
     * Records a completed plan's structural outcome for future GP training.
     * Computes mean actual_reward from task_outcomes (rows with non-null reward).
     * Skips if no reward data is available (GP subsystem not active).
     */
    @Transactional
    public void recordOutcome(UUID planId, int nTasks, boolean hasContext,
                              boolean hasReview, int nBe, int nFe) {
        List<Object[]> outcomes = taskOutcomeRepository.findOutcomesByPlanId(planId);
        if (outcomes.isEmpty()) {
            log.debug("PlanDecompositionPredictor: no task outcomes for plan {}, skipping", planId);
            return;
        }
        double meanReward = outcomes.stream()
                .filter(row -> row[1] != null)
                .mapToDouble(row -> ((Number) row[1]).doubleValue())
                .average()
                .orElse(0.0);
        repository.save(new PlanOutcome(planId, nTasks, hasContext, hasReview, nBe, nFe, meanReward));
        log.debug("PlanDecompositionPredictor: recorded outcome planId={} nTasks={} reward={:.3f}",
                  planId, nTasks, meanReward);
    }

    private GaussianProcessEngine buildEngine() {
        return new GaussianProcessEngine(new RbfKernel(1.0, lengthScale), noiseVariance);
    }

    private float[] toFeatureVector(int nTasks, boolean hasContext,
                                    boolean hasReview, int nBe, int nFe) {
        return new float[]{nTasks, hasContext ? 1f : 0f, hasReview ? 1f : 0f, nBe, nFe};
    }
}
