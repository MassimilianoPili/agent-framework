package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.repository.TokenLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Double-entry token ledger for per-plan token accounting (#33).
 *
 * <p>Always-on (no feature flag) — pure observability with minimal overhead.
 * Each task completion records a DEBIT (tokens consumed). Domain workers that
 * produce a positive {@code aggregatedReward} also earn a CREDIT proportional
 * to the tokens spent × reward. The net balance shows real efficiency.</p>
 *
 * <p>All writes use {@code REQUIRES_NEW} propagation (same pattern as
 * {@link TokenBudgetService#recordUsage}) to avoid being rolled back by
 * caller transaction failures.</p>
 */
@Service
public class TokenLedgerService {

    private static final Logger log = LoggerFactory.getLogger(TokenLedgerService.class);

    /**
     * Worker types that produce direct value (code, schemas, contracts).
     * Only these earn standard credits — infrastructure workers are cost-only
     * (but may earn Shapley credits via {@link #creditShapley}).
     */
    private static final Set<String> CREDIT_ELIGIBLE = Set.of(
            "BE", "FE", "AI_TASK", "CONTRACT", "DBA", "MOBILE"
    );

    /** Returns true if the worker type earns standard credits (not Shapley). */
    public static boolean isCreditEligible(String workerType) {
        return CREDIT_ELIGIBLE.contains(workerType);
    }

    private final TokenLedgerRepository repository;

    public TokenLedgerService(TokenLedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Records a DEBIT entry — tokens consumed by a task.
     *
     * @param planId      the plan
     * @param itemId      the plan item (nullable for plan-level debits)
     * @param taskKey     short task identifier (e.g. "BE-001")
     * @param workerType  the worker type name
     * @param tokens      actual tokens consumed (skipped if ≤ 0)
     * @param description human-readable description
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void debit(UUID planId, UUID itemId, String taskKey,
                      String workerType, long tokens, String description) {
        if (tokens <= 0) return;

        long currentBalance = repository.findLatestBalance(planId).orElse(0L);
        long newBalance = currentBalance - tokens;

        TokenLedger entry = TokenLedger.debit(planId, itemId, taskKey,
                workerType, tokens, newBalance, description);
        repository.save(entry);

        log.debug("Ledger DEBIT: plan={} task={} worker={} amount={} balance={}",
                planId, taskKey, workerType, tokens, newBalance);
    }

    /**
     * Records a CREDIT entry — value produced by a domain worker.
     *
     * <p>Credit amount = {@code Math.round(actualTokens × aggregatedReward)}.
     * Infrastructure workers and tasks with non-positive reward are skipped.</p>
     *
     * @param planId           the plan
     * @param itemId           the plan item
     * @param taskKey          short task identifier
     * @param workerType       the worker type name
     * @param actualTokens     tokens consumed by this task
     * @param aggregatedReward reward score (0.0–1.0+ range)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void credit(UUID planId, UUID itemId, String taskKey,
                       String workerType, long actualTokens, double aggregatedReward) {
        if (!CREDIT_ELIGIBLE.contains(workerType)) return;
        if (aggregatedReward <= 0) return;

        long creditAmount = Math.round(actualTokens * aggregatedReward);
        if (creditAmount <= 0) return;

        long currentBalance = repository.findLatestBalance(planId).orElse(0L);
        long newBalance = currentBalance + creditAmount;

        TokenLedger entry = TokenLedger.credit(planId, itemId, taskKey,
                workerType, creditAmount, newBalance,
                String.format("Reward %.3f × %d tokens", aggregatedReward, actualTokens));
        repository.save(entry);

        log.debug("Ledger CREDIT: plan={} task={} worker={} amount={} reward={} balance={}",
                planId, taskKey, workerType, creditAmount, aggregatedReward, newBalance);
    }

    /**
     * Records a Shapley-derived CREDIT for infrastructure workers (#40).
     *
     * <p>Converts the Shapley value fraction into token-equivalent credit using
     * the plan's total token consumption as the base.</p>
     *
     * @param planId          the plan
     * @param itemId          the plan item
     * @param taskKey         short task identifier
     * @param workerType      the worker type name
     * @param shapleyValue    the computed Shapley value (φᵢ)
     * @param planTotalTokens total tokens consumed across the plan
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void creditShapley(UUID planId, UUID itemId, String taskKey,
                              String workerType, double shapleyValue, long planTotalTokens) {
        if (shapleyValue <= 0) return;

        long creditAmount = Math.round(shapleyValue * planTotalTokens);
        if (creditAmount <= 0) return;

        long currentBalance = repository.findLatestBalance(planId).orElse(0L);
        long newBalance = currentBalance + creditAmount;

        TokenLedger entry = TokenLedger.credit(planId, itemId, taskKey,
                workerType, creditAmount, newBalance,
                String.format("Shapley DAG credit: φ=%.4f", shapleyValue));
        repository.save(entry);

        log.debug("Ledger SHAPLEY CREDIT: plan={} task={} worker={} amount={} φ={} balance={}",
                planId, taskKey, workerType, creditAmount, shapleyValue, newBalance);
    }

    /** Returns the current balance for a plan (0 if no entries). */
    public long currentBalance(UUID planId) {
        return repository.findLatestBalance(planId).orElse(0L);
    }

    /** Returns all ledger entries for a plan, ordered chronologically. */
    public List<TokenLedger> getLedger(UUID planId) {
        return repository.findByPlanIdOrderByCreatedAtAsc(planId);
    }

    /** Returns total debits for a plan. */
    public long sumDebits(UUID planId) {
        return repository.sumDebits(planId);
    }

    /** Returns total credits for a plan. */
    public long sumCredits(UUID planId) {
        return repository.sumCredits(planId);
    }

    /**
     * Computes token efficiency: {@code totalCredits / totalDebits}.
     *
     * <p>A ratio of 1.0 means every token consumed was "repaid" by value produced.
     * Above 1.0 indicates exceptional ROI; below 1.0 is normal (infra overhead).</p>
     *
     * @return efficiency ratio, or 0.0 if no debits recorded
     */
    public double computeEfficiency(UUID planId) {
        long debits = repository.sumDebits(planId);
        if (debits == 0) return 0.0;
        long credits = repository.sumCredits(planId);
        return (double) credits / debits;
    }
}
