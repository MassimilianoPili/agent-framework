package com.agentframework.orchestrator.budget;

import com.agentframework.orchestrator.config.TokenLedgerProperties;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.metrics.OrchestratorMetrics;
import com.agentframework.orchestrator.repository.TokenLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
@EnableConfigurationProperties(TokenLedgerProperties.class)
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
    private final OrchestratorMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenLedgerProperties properties;

    /** Guard: at most 1 low-efficiency alert per plan (soft state, reset on restart). */
    private final Set<UUID> alertedPlans = ConcurrentHashMap.newKeySet();

    public TokenLedgerService(TokenLedgerRepository repository,
                              OrchestratorMetrics metrics,
                              ApplicationEventPublisher eventPublisher,
                              TokenLedgerProperties properties) {
        this.repository = repository;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    // ── Debit ────────────────────────────────────────────────────────────────

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

        metrics.recordLedgerDebit(workerType, tokens);

        log.debug("Ledger DEBIT: plan={} task={} worker={} amount={} balance={}",
                planId, taskKey, workerType, tokens, newBalance);

        checkLowEfficiency(planId);
    }

    // ── Credit ───────────────────────────────────────────────────────────────

    /**
     * Records a CREDIT entry — value produced by a domain worker.
     *
     * <p>Credit amount = {@code Math.round(actualTokens × aggregatedReward)}.
     * Infrastructure workers and tasks with non-positive reward are skipped.</p>
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

        metrics.recordLedgerCredit(workerType, creditAmount, "standard");

        log.debug("Ledger CREDIT: plan={} task={} worker={} amount={} reward={} balance={}",
                planId, taskKey, workerType, creditAmount, aggregatedReward, newBalance);
    }

    /**
     * Records a Shapley-derived CREDIT for infrastructure workers (#40).
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

        metrics.recordLedgerCredit(workerType, creditAmount, "shapley");

        log.debug("Ledger SHAPLEY CREDIT: plan={} task={} worker={} amount={} φ={} balance={}",
                planId, taskKey, workerType, creditAmount, shapleyValue, newBalance);
    }

    // ── Queries ──────────────────────────────────────────────────────────────

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
     * @return efficiency ratio, or 0.0 if no debits recorded
     */
    public double computeEfficiency(UUID planId) {
        long debits = repository.sumDebits(planId);
        if (debits == 0) return 0.0;
        long credits = repository.sumCredits(planId);
        return (double) credits / debits;
    }

    // ── Per-workerType efficiency (#33 observability) ────────────────────────

    /** Per-worker-type efficiency breakdown. */
    public record WorkerTypeEfficiency(long debits, long credits, double efficiency) {}

    /**
     * Computes efficiency broken down by worker type.
     *
     * @return map of workerType → (debits, credits, efficiency)
     */
    public Map<String, WorkerTypeEfficiency> computeEfficiencyByWorkerType(UUID planId) {
        Map<String, Long> debitsByType = parseGroupByResults(repository.sumDebitsByWorkerType(planId));
        Map<String, Long> creditsByType = parseGroupByResults(repository.sumCreditsByWorkerType(planId));

        Set<String> allTypes = new TreeSet<>();
        allTypes.addAll(debitsByType.keySet());
        allTypes.addAll(creditsByType.keySet());

        Map<String, WorkerTypeEfficiency> result = new LinkedHashMap<>();
        for (String type : allTypes) {
            long d = debitsByType.getOrDefault(type, 0L);
            long c = creditsByType.getOrDefault(type, 0L);
            double eff = d > 0 ? (double) c / d : 0.0;
            result.put(type, new WorkerTypeEfficiency(d, c, eff));
        }
        return result;
    }

    private Map<String, Long> parseGroupByResults(List<Object[]> rows) {
        Map<String, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            String workerType = (String) row[0];
            long amount = ((Number) row[1]).longValue();
            map.put(workerType, amount);
        }
        return map;
    }

    // ── Burn rate ────────────────────────────────────────────────────────────

    /**
     * Computes token burn rate in tokens per minute.
     *
     * @return tokens/minute, or empty if fewer than 2 debit entries
     */
    public OptionalDouble computeBurnRate(UUID planId) {
        List<TokenLedger> entries = repository.findByPlanIdOrderByCreatedAtAsc(planId);

        // Filter to DEBIT entries only
        List<TokenLedger> debits = entries.stream()
                .filter(e -> e.getEntryType() == TokenLedger.EntryType.DEBIT)
                .toList();

        if (debits.size() < 2) return OptionalDouble.empty();

        Instant first = debits.getFirst().getCreatedAt();
        Instant last = debits.getLast().getCreatedAt();
        long durationMinutes = Duration.between(first, last).toMinutes();

        if (durationMinutes <= 0) return OptionalDouble.empty();

        long totalDebits = debits.stream().mapToLong(TokenLedger::getAmount).sum();
        return OptionalDouble.of((double) totalDebits / durationMinutes);
    }

    // ── Low-efficiency alert ─────────────────────────────────────────────────

    private void checkLowEfficiency(UUID planId) {
        try {
            if (alertedPlans.contains(planId)) return;

            long debitCount = repository.countDebits(planId);
            if (debitCount < 3) return;

            double efficiency = computeEfficiency(planId);
            if (efficiency < properties.lowEfficiencyThreshold()) {
                alertedPlans.add(planId);
                log.warn("Low efficiency alert: plan={} efficiency={} (threshold={})",
                        planId, String.format("%.3f", efficiency), properties.lowEfficiencyThreshold());

                String extraJson = String.format(
                        "{\"planId\":\"%s\",\"efficiency\":%.4f,\"threshold\":%.4f}",
                        planId, efficiency, properties.lowEfficiencyThreshold());
                eventPublisher.publishEvent(SpringPlanEvent.forSystem("LOW_EFFICIENCY", extraJson));
            }
        } catch (Exception e) {
            log.debug("Low-efficiency check failed (non-blocking): {}", e.getMessage());
        }
    }
}
