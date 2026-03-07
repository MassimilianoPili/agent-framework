package com.agentframework.orchestrator.gp;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.api.dto.PlanRequest;
import com.agentframework.orchestrator.budget.TokenBudgetService;
import com.agentframework.orchestrator.domain.PlanItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service bridge for Bayesian success prediction — dispatch admission control.
 *
 * <p>Predicts the probability that a task will succeed before dispatch, using a
 * logistic regression model trained on historical {@link TaskOutcome} data.
 * When the predicted probability falls below the admission threshold, the task
 * remains in WAITING state rather than being dispatched (saving worker tokens).</p>
 *
 * <p>Only created when {@code gp.enabled=true} — shares the same conditional as
 * {@link TaskOutcomeService} since both depend on task outcome data.</p>
 *
 * @see BayesianSuccessPredictor
 * @see SuccessPrediction
 */
@Service
@ConditionalOnProperty(prefix = "gp", name = "enabled", havingValue = "true")
public class BayesianSuccessPredictorService {

    private static final Logger log = LoggerFactory.getLogger(BayesianSuccessPredictorService.class);

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final TokenBudgetService tokenBudgetService;
    private volatile BayesianSuccessPredictor predictor;

    public BayesianSuccessPredictorService(TaskOutcomeRepository taskOutcomeRepository,
                                            TokenBudgetService tokenBudgetService) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.tokenBudgetService = tokenBudgetService;
    }

    @PostConstruct
    void init() {
        retrain();
    }

    /**
     * Predicts success probability for a plan item using available signals.
     *
     * @param item          the plan item about to be dispatched
     * @param gpPrediction  GP prediction for this task (may be null)
     * @param budget        the plan's budget (for remaining budget feature)
     * @param planId        the plan ID (for budget lookup)
     * @return prediction with calibrated probability and dispatch recommendation
     */
    public SuccessPrediction predictForItem(PlanItem item, GpPrediction gpPrediction,
                                              PlanRequest.Budget budget, UUID planId) {
        Double gpMu = gpPrediction != null ? gpPrediction.mu() : null;
        Double gpSigma2 = gpPrediction != null ? gpPrediction.sigma2() : null;

        // Compute remaining budget for this worker type
        Long budgetRemaining = null;
        if (budget != null && budget.perWorkerType() != null) {
            Long limit = budget.perWorkerType().get(item.getWorkerType().name());
            if (limit != null) {
                long used = tokenBudgetService.currentUsage(planId, item.getWorkerType().name());
                budgetRemaining = Math.max(0, limit - used);
            }
        }

        double[] features = BayesianSuccessPredictor.buildFeatureVector(
                null,          // task embedding not available at dispatch time in service layer
                gpMu,
                gpSigma2,
                null,          // elo_at_dispatch — loaded separately if needed
                null,          // context_quality — not yet tracked
                budgetRemaining
        );

        return predictor.predict(features);
    }

    /**
     * Retrains the predictor from current task_outcomes.
     * Runs hourly by default, and at startup.
     */
    @Scheduled(fixedDelayString = "${bayesian.retrain-interval-ms:3600000}")
    public void retrain() {
        try {
            List<Object[]> rows = taskOutcomeRepository.findRewardsByWorkerType();
            if (rows.size() < BayesianSuccessPredictor.MIN_TRAINING_SIZE) {
                predictor = BayesianSuccessPredictor.prior();
                log.info("Bayesian predictor: insufficient data ({} rows), using prior", rows.size());
                return;
            }

            // Build feature/outcome arrays from task_outcomes
            // For retrain, we use a simplified feature vector (no embeddings,
            // only scalar features from the stored GP predictions)
            double[][] features = new double[rows.size()][BayesianSuccessPredictor.FEATURE_DIM];
            boolean[] outcomes = new boolean[rows.size()];

            for (int i = 0; i < rows.size(); i++) {
                double reward = ((Number) rows.get(i)[1]).doubleValue();
                outcomes[i] = reward >= BayesianSuccessPredictor.SUCCESS_REWARD_THRESHOLD;
                // Feature vector: zeros for embedding, reward as a proxy for mu
                features[i][1024] = reward;  // use historical reward as proxy for gp_mu
                features[i][1025] = 1.0;     // default sigma2
            }

            predictor = BayesianSuccessPredictor.train(features, outcomes);
            log.info("Bayesian predictor retrained from {} outcomes", rows.size());
        } catch (Exception e) {
            log.warn("Bayesian predictor retrain failed (non-blocking): {}", e.getMessage());
            if (predictor == null) {
                predictor = BayesianSuccessPredictor.prior();
            }
        }
    }
}
