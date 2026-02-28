package com.agentframework.orchestrator.reward;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Updates ELO ratings for worker profiles based on completed plan outcomes.
 *
 * <p>ELO is computed per-plan by grouping DONE items by their workerType, then
 * running pairwise comparisons between different profiles within each group.
 * This mirrors how chess tournaments work: each pairing produces one match result.</p>
 *
 * <p>For example, if plan P has:</p>
 * <pre>
 *   BE-001 (be-java, reward=0.8)
 *   BE-002 (be-go,   reward=0.3)
 * </pre>
 * <p>→ be-java wins the pairing → be-java ELO increases, be-go decreases.</p>
 *
 * <p>Called once per plan from {@link com.agentframework.orchestrator.orchestration.QualityGateService}
 * after all reward signals have been assigned.</p>
 */
@Service
public class EloRatingService {

    private static final Logger log = LoggerFactory.getLogger(EloRatingService.class);

    private final PlanItemRepository planItemRepository;
    private final WorkerEloStatsRepository eloStatsRepository;

    public EloRatingService(PlanItemRepository planItemRepository,
                            WorkerEloStatsRepository eloStatsRepository) {
        this.planItemRepository = planItemRepository;
        this.eloStatsRepository = eloStatsRepository;
    }

    /**
     * Runs ELO updates for all worker profiles that participated in the given plan.
     *
     * <p>Only DONE items with a non-null aggregatedReward and a non-null workerProfile
     * are eligible. Items with null reward are skipped.</p>
     */
    @Transactional
    public void updateRatingsForPlan(UUID planId) {
        List<PlanItem> items = planItemRepository.findByPlanId(planId);

        // Only consider successfully completed items with profiles and rewards
        List<PlanItem> eligible = items.stream()
                .filter(i -> i.getStatus() == ItemStatus.DONE)
                .filter(i -> i.getWorkerProfile() != null)
                .filter(i -> i.getAggregatedReward() != null)
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            log.debug("No eligible items for ELO update (plan={})", planId);
            return;
        }

        // Record individual reward contributions (cumulative average tracking)
        for (PlanItem item : eligible) {
            WorkerEloStats stats = getOrCreate(item.getWorkerProfile());
            stats.recordReward(item.getAggregatedReward());
            eloStatsRepository.save(stats);
        }

        // Group by workerType for pairwise comparisons
        Map<String, List<PlanItem>> byWorkerType = eligible.stream()
                .collect(Collectors.groupingBy(i -> i.getWorkerType().name()));

        int totalMatches = 0;
        for (Map.Entry<String, List<PlanItem>> entry : byWorkerType.entrySet()) {
            List<PlanItem> group = entry.getValue();
            if (group.size() < 2) continue; // need at least 2 profiles to compare

            // Group further by workerProfile within this workerType
            Map<String, List<PlanItem>> byProfile = group.stream()
                    .collect(Collectors.groupingBy(PlanItem::getWorkerProfile));

            List<String> profiles = new ArrayList<>(byProfile.keySet());

            // All-pairs ELO matches: each pair (A, B) with A ≠ B
            for (int i = 0; i < profiles.size(); i++) {
                for (int j = i + 1; j < profiles.size(); j++) {
                    String profileA = profiles.get(i);
                    String profileB = profiles.get(j);

                    double rewardA = averageReward(byProfile.get(profileA));
                    double rewardB = averageReward(byProfile.get(profileB));

                    WorkerEloStats statsA = getOrCreate(profileA);
                    WorkerEloStats statsB = getOrCreate(profileB);

                    double eloA = statsA.getEloRating();
                    double eloB = statsB.getEloRating();

                    boolean aWins = rewardA > rewardB;
                    boolean bWins = rewardB > rewardA;

                    statsA.applyEloUpdate(eloB, aWins);
                    statsB.applyEloUpdate(eloA, bWins);

                    eloStatsRepository.save(statsA);
                    eloStatsRepository.save(statsB);

                    log.debug("ELO match ({} vs {}) type={}: rewardA={} rewardB={} → eloA={} eloB={}",
                              profileA, profileB, entry.getKey(),
                              rewardA, rewardB,
                              statsA.getEloRating(), statsB.getEloRating());
                    totalMatches++;
                }
            }
        }

        log.info("ELO update complete for plan {}: {} pairwise matches across {} profiles",
                 planId, totalMatches, eligible.stream()
                         .map(PlanItem::getWorkerProfile).distinct().count());
    }

    private WorkerEloStats getOrCreate(String profile) {
        return eloStatsRepository.findById(profile)
                .orElseGet(() -> new WorkerEloStats(profile));
    }

    private double averageReward(List<PlanItem> items) {
        return items.stream()
                .mapToDouble(i -> i.getAggregatedReward())
                .average()
                .orElse(0.0);
    }
}
