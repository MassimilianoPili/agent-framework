package com.agentframework.orchestrator.reward;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST API for reward signal export and ELO stats.
 *
 * <p>All endpoints produce data useful for offline ML training pipelines:
 * <ul>
 *   <li>{@code GET /api/v1/rewards} — per-task reward records (NDJSON, DPO-ready)</li>
 *   <li>{@code GET /api/v1/rewards/stats} — ELO leaderboard per worker profile</li>
 *   <li>{@code GET /api/v1/rewards/preference-pairs} — DPO preference pairs (NDJSON)</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/rewards")
public class RewardController {

    private static final Logger log = LoggerFactory.getLogger(RewardController.class);
    private static final String NDJSON = "application/x-ndjson";

    private final PlanItemRepository planItemRepository;
    private final WorkerEloStatsRepository eloStatsRepository;
    private final PreferencePairRepository pairRepository;

    public RewardController(PlanItemRepository planItemRepository,
                            WorkerEloStatsRepository eloStatsRepository,
                            PreferencePairRepository pairRepository) {
        this.planItemRepository = planItemRepository;
        this.eloStatsRepository = eloStatsRepository;
        this.pairRepository = pairRepository;
    }

    /**
     * Returns per-task reward records as NDJSON (one JSON object per line).
     *
     * <p>Optionally filtered by planId. Only items with at least one reward signal are returned.
     * Format is DPO-ready: includes task metadata, result, and all reward components.</p>
     *
     * <p>Example record:</p>
     * <pre>
     * {"taskKey":"BE-001","workerType":"BE","workerProfile":"be-java","status":"DONE",
     *  "aggregatedReward":0.72,"reviewScore":0.8,"processScore":0.6,
     *  "rewardSources":{"review":0.8,"process":0.6,"quality_gate":null,...}}
     * </pre>
     */
    @GetMapping(produces = NDJSON)
    public ResponseEntity<String> exportRewards(@RequestParam Optional<UUID> planId) {
        List<PlanItem> items;

        if (planId.isPresent()) {
            items = planItemRepository.findByPlanId(planId.get());
        } else {
            items = planItemRepository.findAll();
        }

        String ndjson = items.stream()
                .filter(i -> i.getAggregatedReward() != null
                          || i.getReviewScore() != null
                          || i.getProcessScore() != null)
                .map(this::toRewardRecord)
                .collect(Collectors.joining("\n"));

        log.debug("Exported {} reward records (planId={})", ndjson.isEmpty() ? 0 : ndjson.split("\n").length, planId.orElse(null));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON))
                .body(ndjson);
    }

    /**
     * Returns ELO leaderboard — one entry per worker profile, sorted by ELO rating descending.
     */
    @GetMapping("/stats")
    public List<EloStatResponse> getStats() {
        return eloStatsRepository.findAllByOrderByEloRatingDesc().stream()
                .map(s -> new EloStatResponse(
                        s.getWorkerProfile(),
                        s.getEloRating(),
                        s.getMatchCount(),
                        s.getWinCount(),
                        s.avgReward()))
                .collect(Collectors.toList());
    }

    /**
     * Returns DPO-format preference pairs as NDJSON.
     *
     * @param minDelta minimum reward delta for inclusion (default 0.3)
     * @param limit    maximum number of pairs to return (default 500)
     */
    @GetMapping(value = "/preference-pairs", produces = NDJSON)
    public ResponseEntity<String> exportPreferencePairs(
            @RequestParam(defaultValue = "0.3") float minDelta,
            @RequestParam(defaultValue = "500") int limit) {

        List<PreferencePair> pairs = pairRepository.findByMinDelta(minDelta, limit);

        String ndjson = pairs.stream()
                .map(this::toDpoPairRecord)
                .collect(Collectors.joining("\n"));

        log.debug("Exported {} preference pairs (minDelta={}, limit={})", pairs.size(), minDelta, limit);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON))
                .body(ndjson);
    }

    // ── Private serializers ───────────────────────────────────────────────────

    private String toRewardRecord(PlanItem item) {
        return String.format(
                "{\"taskKey\":%s,\"workerType\":%s,\"workerProfile\":%s,\"status\":%s,"
                + "\"aggregatedReward\":%s,\"reviewScore\":%s,\"processScore\":%s,"
                + "\"rewardSources\":%s,\"result\":%s}",
                jsonStr(item.getTaskKey()),
                jsonStr(item.getWorkerType().name()),
                jsonStr(item.getWorkerProfile()),
                jsonStr(item.getStatus().name()),
                item.getAggregatedReward() != null ? item.getAggregatedReward() : "null",
                item.getReviewScore() != null ? item.getReviewScore() : "null",
                item.getProcessScore() != null ? item.getProcessScore() : "null",
                item.getRewardSources() != null ? item.getRewardSources() : "null",
                item.getResult() != null ? item.getResult() : "null");
    }

    private String toDpoPairRecord(PreferencePair pair) {
        return String.format(
                "{\"id\":%s,\"planId\":%s,\"taskKey\":%s,\"workerType\":%s,"
                + "\"prompt\":%s,\"chosen\":%s,\"rejected\":%s,"
                + "\"chosenReward\":%s,\"rejectedReward\":%s,\"deltaReward\":%s,"
                + "\"generationSource\":%s,\"createdAt\":%s}",
                jsonStr(pair.getId().toString()),
                jsonStr(pair.getPlanId() != null ? pair.getPlanId().toString() : null),
                jsonStr(pair.getTaskKey()),
                jsonStr(pair.getWorkerType()),
                pair.getPromptText(),
                pair.getChosenResult(),
                pair.getRejectedResult(),
                pair.getChosenReward(),
                pair.getRejectedReward(),
                pair.getDeltaReward(),
                jsonStr(pair.getGenerationSource()),
                jsonStr(pair.getCreatedAt().toString()));
    }

    private String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── Response DTOs ────────────────────────────────────────────────────────

    public record EloStatResponse(
            String workerProfile,
            double eloRating,
            int matchCount,
            int winCount,
            double avgReward) {}
}
