package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.api.dto.PlanRequest.Budget;
import com.agentframework.orchestrator.domain.PlanTokenUsage;
import com.agentframework.orchestrator.repository.PlanTokenUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final PlanTokenUsageRepository usageRepository;

    public TokenBudgetService(PlanTokenUsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    /**
     * Checks whether dispatching a task of the given workerType is allowed under the budget.
     *
     * @param planId     the plan being orchestrated
     * @param workerType the type of the worker about to be dispatched
     * @param budget     the budget configuration attached to this plan (null = no budget)
     * @return the enforcement decision — callers act on {@link BudgetDecision#action()}
     */
    public BudgetDecision checkBudget(UUID planId, String workerType, Budget budget) {
        if (budget == null || budget.perWorkerType() == null) {
            return BudgetDecision.ALLOW;
        }
        Long limit = budget.perWorkerType().get(workerType);
        if (limit == null) {
            return BudgetDecision.ALLOW; // no limit configured for this worker type
        }

        long used = currentUsage(planId, workerType);
        if (used >= limit) {
            String policy = budget.onExceeded() != null ? budget.onExceeded() : ON_EXCEEDED_NO_NEW_DISPATCH;
            log.warn("Token budget exceeded for plan={} workerType={}: used={} limit={} policy={}",
                     planId, workerType, used, limit, policy);
            return BudgetDecision.exceeded(policy, used, limit);
        }
        return BudgetDecision.ALLOW;
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
     */
    public record BudgetDecision(Action action, long used, long limit) {

        public static final BudgetDecision ALLOW = new BudgetDecision(Action.ALLOW, 0, Long.MAX_VALUE);

        public static BudgetDecision exceeded(String policy, long used, long limit) {
            Action action = switch (policy) {
                case ON_EXCEEDED_FAIL_FAST       -> Action.FAIL;
                case ON_EXCEEDED_NO_NEW_DISPATCH -> Action.SKIP;
                default                          -> Action.WARN; // SOFT_LIMIT
            };
            return new BudgetDecision(action, used, limit);
        }

        public boolean isBlocked() { return action == Action.FAIL || action == Action.SKIP; }

        public enum Action { ALLOW, SKIP, FAIL, WARN }
    }
}
