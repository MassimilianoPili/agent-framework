package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Detects Byzantine worker failures using majority voting (PBFT-inspired).
 *
 * <p>Byzantine Fault Tolerance (Lamport, Shostak &amp; Pease, 1982) ensures that a
 * distributed system reaches consensus even when some participants send arbitrarily
 * incorrect results.  Applied here: when a task is dispatched to multiple workers
 * (via retries), we vote on success/failure and identify workers whose result
 * diverges from the majority as "Byzantine".</p>
 *
 * <p>Consensus rule (PBFT threshold):
 * <pre>
 *   consensus_outcome = "success" if (successes / total) &gt; majority_threshold
 *                       "failure" otherwise
 *   consensus_reached = majority count &gt; majority_threshold × total
 *   byzantine_workers = attempts whose result ≠ consensus_outcome
 * </pre>
 * Default threshold: 0.67 (2/3 majority, BFT guarantee with ≤ 1/3 Byzantine nodes).</p>
 *
 * @see <a href="https://doi.org/10.1145/357172.357176">
 *     Lamport, Shostak &amp; Pease (1982), Byzantine Generals Problem</a>
 */
@Service
@ConditionalOnProperty(prefix = "bft", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ByzantineFaultToleranceService {

    private static final Logger log = LoggerFactory.getLogger(ByzantineFaultToleranceService.class);

    private final DispatchAttemptRepository dispatchAttemptRepository;
    private final PlanItemRepository        planItemRepository;
    private final TaskOutcomeRepository     taskOutcomeRepository;

    @Value("${bft.majority-threshold:0.67}")
    private double majorityThreshold;

    public ByzantineFaultToleranceService(DispatchAttemptRepository dispatchAttemptRepository,
                                           PlanItemRepository planItemRepository,
                                           TaskOutcomeRepository taskOutcomeRepository) {
        this.dispatchAttemptRepository = dispatchAttemptRepository;
        this.planItemRepository        = planItemRepository;
        this.taskOutcomeRepository     = taskOutcomeRepository;
    }

    /**
     * Runs BFT consensus analysis for a specific plan item that has multiple attempts.
     *
     * @param itemId plan item UUID
     * @return BFT consensus report
     */
    public BFTConsensusReport analyseItem(UUID itemId) {
        var attempts = dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId);

        if (attempts.isEmpty()) {
            return new BFTConsensusReport("unknown", false, 0, 0, List.of());
        }

        int totalVoters = attempts.size();
        long successCount = attempts.stream().filter(a -> a.isSuccess()).count();
        long failureCount = totalVoters - successCount;

        // Majority vote
        boolean majoritySuccess = (double) successCount / totalVoters > majorityThreshold;
        String consensusOutcome = majoritySuccess ? "success" : "failure";
        int majorityVotes = (int) (majoritySuccess ? successCount : failureCount);
        boolean consensusReached = (double) majorityVotes / totalVoters > majorityThreshold;

        // Identify Byzantine workers: those whose result ≠ consensus
        List<String> byzantineWorkers = new ArrayList<>();
        for (var attempt : attempts) {
            boolean agreedWithConsensus = attempt.isSuccess() == majoritySuccess;
            if (!agreedWithConsensus) {
                // Identify by attempt number (worker profile not stored on DispatchAttempt)
                byzantineWorkers.add("attempt-" + attempt.getAttemptNumber());
            }
        }

        log.debug("BFT for item {}: {} voters, consensus='{}', reached={}, byzantine={}",
                  itemId, totalVoters, consensusOutcome, consensusReached, byzantineWorkers.size());

        return new BFTConsensusReport(consensusOutcome, consensusReached,
                                       majorityVotes, totalVoters, byzantineWorkers);
    }

    /**
     * Runs BFT consensus analysis for all items in a plan that have multiple attempts.
     * Returns a map of itemId → BFTConsensusReport for items with ≥ 2 attempts.
     *
     * @param planId plan UUID
     * @return map of item ID to consensus report (only items with multiple attempts)
     */
    public Map<UUID, BFTConsensusReport> analyseAllRetries(UUID planId) {
        var items = planItemRepository.findByPlanId(planId);
        Map<UUID, BFTConsensusReport> results = new LinkedHashMap<>();

        for (var item : items) {
            var attempts = dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(item.getId());
            if (attempts.size() >= 2) {
                results.put(item.getId(), analyseItem(item.getId()));
            }
        }

        log.debug("BFT for plan {}: {} items with multiple attempts analysed", planId, results.size());
        return results;
    }

    /**
     * BFT consensus report for a plan item.
     *
     * @param consensusOutcome  "success" or "failure" determined by majority vote
     * @param consensusReached  true if majority count &gt; threshold × total
     * @param majorityVotes     number of attempts agreeing with the consensus outcome
     * @param totalVoters       total number of dispatch attempts for this item
     * @param byzantineWorkers  identifiers of attempts that diverged from consensus
     */
    public record BFTConsensusReport(
            String consensusOutcome,
            boolean consensusReached,
            int majorityVotes,
            int totalVoters,
            List<String> byzantineWorkers
    ) {}
}
