package com.agentframework.orchestrator.budget;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.api.dto.PlanRequest.Budget;
import com.agentframework.orchestrator.budget.PortfolioOptimizer.PortfolioResult;
import com.agentframework.orchestrator.domain.PlanTokenUsage;
import com.agentframework.orchestrator.repository.PlanTokenUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks per-plan, per-workerType token consumption and enforces budget limits.
 *
 * <p>Three budget enforcement modes (controlled by {@code Budget.onExceeded()}):
 * <ul>
 *   <li>{@code FAIL_FAST} — dispatching is blocked; the item transitions to FAILED immediately</li>
 *   <li>{@code NO_NEW_DISPATCH} — dispatching is skipped (item stays WAITING) until budget resets</li>
 *   <li>{@code SOFT_LIMIT} — logs a warning but dispatch proceeds anyway</li>
 * </ul>
 *
 * <p>Token usage is stored in {@code plan_token_usage} and incremented atomically via a single
 * {@code UPDATE} — no Redis or distributed lock needed.
 *
 * <p>When a {@link GpPrediction} is available, the static limit is modulated:
 * {@code effectiveLimit = base × (1 + alpha × sigma²) × clip(mu, 0.3, 1.0)}.
 * High uncertainty (sigma²) increases the budget (active learning exploration),
 * while low expected quality (mu) decreases it (avoid wasting tokens on likely-failing tasks).
 */
@Service
public class TokenBudgetService {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetService.class);

    /** Budget policy: block dispatch, keep item WAITING */
    public static final String ON_EXCEEDED_NO_NEW_DISPATCH = "NO_NEW_DISPATCH";
    /** Budget policy: fail the item immediately */
    public static final String ON_EXCEEDED_FAIL_FAST = "FAIL_FAST";
    /** Budget policy: log warning, proceed anyway */
    public static final String ON_EXCEEDED_SOFT_LIMIT = "SOFT_LIMIT";

    private static final double MU_MIN = 0.3;
    private static final double MU_MAX = 1.0;

    private final PlanTokenUsageRepository usageRepository;
    private final double alpha;

    public TokenBudgetService(PlanTokenUsageRepository usageRepository,
                               @Value("${gp.budget.alpha:1.0}") double alpha) {
        this.usageRepository = usageRepository;
        this.alpha = alpha;
    }

    /**
     * Checks whether dispatching a task is allowed under the budget (static limit).
     * Delegates to {@link #checkBudget(UUID, String, Budget, GpPrediction)} with no prediction.
     */
    public BudgetDecision checkBudget(UUID planId, String workerType, Budget budget) {
        return checkBudget(planId, workerType, budget, null);
    }

    /**
     * Checks whether dispatching a task is allowed under the budget, optionally
     * adjusted by a GP prediction.
     *
     * <p>When {@code prediction} is non-null, the static limit is modulated:
     * {@code effectiveLimit = limit × (1 + alpha × sigma²) × clip(mu, 0.3, 1.0)}.
     * When null, the effective limit equals the static limit (backward compatible).
     *
     * @param planId     the plan being orchestrated
     * @param workerType the type of the worker about to be dispatched
     * @param budget     the budget configuration attached to this plan (null = no budget)
     * @param prediction GP prediction for this task+profile (null = use static limit)
     * @return the enforcement decision — callers act on {@link BudgetDecision#action()}
     */
    public BudgetDecision checkBudget(UUID planId, String workerType, Budget budget,
                                       GpPrediction prediction) {
        if (budget == null || budget.perWorkerType() == null) {
            return BudgetDecision.ALLOW;
        }
        Long limit = budget.perWorkerType().get(workerType);
        if (limit == null) {
            return BudgetDecision.ALLOW;
        }

        long effectiveLimit = computeEffectiveLimit(limit, prediction);
        long used = currentUsage(planId, workerType);

        if (used >= effectiveLimit) {
            String policy = budget.onExceeded() != null ? budget.onExceeded() : ON_EXCEEDED_NO_NEW_DISPATCH;
            log.warn("Token budget exceeded for plan={} workerType={}: used={} limit={} effective={} policy={}{}",
                     planId, workerType, used, limit, effectiveLimit, policy,
                     prediction != null ? String.format(" (mu=%.2f, sigma2=%.2f)", prediction.mu(), prediction.sigma2()) : "");
            return BudgetDecision.exceeded(policy, used, limit, effectiveLimit);
        }
        return BudgetDecision.ALLOW;
    }

    /**
     * Computes the effective budget limit adjusted by GP prediction.
     *
     * <p>Formula: {@code base × (1 + alpha × sigma²) × clip(mu, 0.3, 1.0)}
     * <ul>
     *   <li>sigma² high → more budget (uncertain task needs exploration)</li>
     *   <li>mu low → less budget (likely-failing task shouldn't waste tokens)</li>
     *   <li>no prediction → effective = base (backward compatible)</li>
     * </ul>
     */
    long computeEffectiveLimit(long baseLimit, GpPrediction prediction) {
        if (prediction == null) {
            return baseLimit;
        }
        double uncertaintyFactor = 1.0 + alpha * prediction.sigma2();
        double qualityFactor = Math.max(MU_MIN, Math.min(MU_MAX, prediction.mu()));
        return Math.round(baseLimit * uncertaintyFactor * qualityFactor);
    }

    /**
     * Records actual token consumption after a task completes.
     * Atomically increments the counter; creates the row if it doesn't exist yet.
     *
     * @param planId     the plan
     * @param workerType the worker that just finished
     * @param tokensUsed actual tokens consumed (0 if unknown)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUsage(UUID planId, String workerType, long tokensUsed) {
        if (tokensUsed <= 0) return;

        int updated = usageRepository.incrementTokensUsed(planId, workerType, tokensUsed);
        if (updated == 0) {
            // Row doesn't exist yet — create it, then increment atomically
            PlanTokenUsage row = new PlanTokenUsage(planId, workerType);
            usageRepository.save(row);
            usageRepository.incrementTokensUsed(planId, workerType, tokensUsed);
        }
        log.debug("Token usage recorded: plan={} workerType={} delta={}", planId, workerType, tokensUsed);
    }

    /**
     * Adjusts per-workerType budget limits using portfolio weights.
     *
     * <p>{@code effectiveLimit_i = base_limit_i × (weight_i / uniform_weight)} where
     * {@code uniform = 1/N}. Shifts budget toward worker types with better risk-adjusted return.</p>
     *
     * @param budget     the original budget (null-safe — returns as-is if null)
     * @param portfolio  portfolio optimization result with per-type weights
     * @return adjusted budget with reallocated per-workerType limits
     */
    public Budget adjustWithPortfolio(Budget budget, PortfolioResult portfolio) {
        if (budget == null || budget.perWorkerType() == null || portfolio.weights().isEmpty()) {
            return budget;
        }
        int n = portfolio.weights().size();
        double uniform = 1.0 / n;
        Map<String, Long> adjusted = new LinkedHashMap<>();
        for (var entry : budget.perWorkerType().entrySet()) {
            double weight = portfolio.weights().getOrDefault(entry.getKey(), uniform);
            long newLimit = Math.round(entry.getValue() * (weight / uniform));
            adjusted.put(entry.getKey(), Math.max(newLimit, 1)); // minimum 1 token
        }
        return new Budget(budget.onExceeded(), adjusted);
    }

    /** Returns current token usage for a (plan, workerType) pair, 0 if no row yet. */
    public long currentUsage(UUID planId, String workerType) {
        return usageRepository.findByPlanIdAndWorkerType(planId, workerType)
                .map(PlanTokenUsage::getTokensUsed)
                .orElse(0L);
    }

    // ── Inner type ────────────────────────────────────────────────────────────

    /**
     * Decision returned by {@link #checkBudget}: whether dispatch should proceed,
     * be skipped (item stays WAITING), or fail the item.
     *
     * @param effectiveLimit the GP-adjusted limit (equals {@code limit} when no prediction)
     */
    public record BudgetDecision(Action action, long used, long limit, long effectiveLimit) {

        public static final BudgetDecision ALLOW = new BudgetDecision(Action.ALLOW, 0, Long.MAX_VALUE, Long.MAX_VALUE);

        public static BudgetDecision exceeded(String policy, long used, long limit, long effectiveLimit) {
            Action action = switch (policy) {
                case ON_EXCEEDED_FAIL_FAST       -> Action.FAIL;
                case ON_EXCEEDED_NO_NEW_DISPATCH -> Action.SKIP;
                default                          -> Action.WARN; // SOFT_LIMIT
            };
            return new BudgetDecision(action, used, limit, effectiveLimit);
        }

        public boolean isBlocked() { return action == Action.FAIL || action == Action.SKIP; }

        public enum Action { ALLOW, SKIP, FAIL, WARN }
    }
}
