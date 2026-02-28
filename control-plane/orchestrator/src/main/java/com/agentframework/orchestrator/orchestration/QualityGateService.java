package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.QualityGateReport;
import com.agentframework.orchestrator.event.PlanCompletedEvent;
import com.agentframework.orchestrator.planner.PromptLoader;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.repository.QualityGateReportRepository;
import com.agentframework.orchestrator.reward.EloRatingService;
import com.agentframework.orchestrator.reward.PreferencePairGenerator;
import com.agentframework.orchestrator.reward.RewardComputationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a QualityGateReport after all plan items have completed.
 * Uses Claude (review agent profile) to analyze all task results
 * and produce a pass/fail assessment with findings.
 */
@Service
public class QualityGateService {

    private static final Logger log = LoggerFactory.getLogger(QualityGateService.class);

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final PlanRepository planRepository;
    private final PlanItemRepository planItemRepository;
    private final QualityGateReportRepository reportRepository;
    private final ObjectMapper objectMapper;
    private final RewardComputationService rewardComputationService;
    private final EloRatingService eloRatingService;
    private final PreferencePairGenerator preferencePairGenerator;

    public QualityGateService(ChatClient chatClient,
                              PromptLoader promptLoader,
                              PlanRepository planRepository,
                              PlanItemRepository planItemRepository,
                              QualityGateReportRepository reportRepository,
                              ObjectMapper objectMapper,
                              RewardComputationService rewardComputationService,
                              EloRatingService eloRatingService,
                              PreferencePairGenerator preferencePairGenerator) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
        this.planRepository = planRepository;
        this.planItemRepository = planItemRepository;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
        this.rewardComputationService = rewardComputationService;
        this.eloRatingService = eloRatingService;
        this.preferencePairGenerator = preferencePairGenerator;
    }

    /**
     * Reacts to plan completion by generating a quality gate report.
     * Loads the plan and its items from the database, then calls Claude
     * with all task results to produce a structured assessment.
     */
    @Async("orchestratorAsyncExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPlanCompleted(PlanCompletedEvent event) {
        Plan plan = planRepository.findById(event.planId())
            .orElseThrow(() -> new IllegalStateException(
                "Plan not found for quality gate: " + event.planId()));
        List<PlanItem> items = planItemRepository.findByPlanId(event.planId());

        String profileSummary = items.stream()
            .filter(i -> i.getWorkerProfile() != null)
            .collect(Collectors.groupingBy(PlanItem::getWorkerProfile, Collectors.counting()))
            .entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", "));
        log.info("Generating quality gate report for plan {} ({} items, profiles: [{}])",
                 plan.getId(), items.size(), profileSummary.isEmpty() ? "none" : profileSummary);

        try {
            String allResultsJson = serializeResults(items);

            String systemPrompt = promptLoader.load("prompts/review.agent.md");
            String userPrompt = promptLoader.renderQualityGatePrompt(
                plan.getId().toString(),
                allResultsJson,
                "80"
            );

            String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

            QualityGateResult parsed = parseResponse(response);

            QualityGateReport report = new QualityGateReport(
                UUID.randomUUID(),
                plan,
                parsed.passed(),
                parsed.summary(),
                parsed.findings()
            );

            reportRepository.save(report);
            log.info("Quality gate report saved for plan {} — passed: {}", plan.getId(), report.isPassed());

            // Reward signal — point 3: distribute quality gate score as fallback for items without reviewScore
            rewardComputationService.distributeQualityGateSignal(plan.getId(), report.isPassed());

            // ELO update: run pairwise profile comparisons for this plan
            eloRatingService.updateRatingsForPlan(plan.getId());

            // DPO preference pair generation: cross-profile and retry-based
            int pairs = preferencePairGenerator.generateForPlan(plan.getId());
            if (pairs > 0) {
                log.info("Generated {} DPO preference pairs for plan {}", pairs, plan.getId());
            }

        } catch (Exception e) {
            log.error("Failed to generate quality gate report for plan {}: {}",
                      plan.getId(), e.getMessage(), e);

            QualityGateReport failReport = new QualityGateReport(
                UUID.randomUUID(),
                plan,
                false,
                "Quality gate generation failed: " + e.getMessage(),
                List.of("ERROR: " + e.getMessage())
            );
            reportRepository.save(failReport);
        }
    }

    private String serializeResults(List<PlanItem> items) {
        List<Map<String, Object>> results = items.stream()
            .map(item -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("taskKey", item.getTaskKey());
                entry.put("workerType", item.getWorkerType().name());
                entry.put("workerProfile", item.getWorkerProfile());
                entry.put("status", item.getStatus().name());
                entry.put("result", item.getResult());
                entry.put("failureReason", item.getFailureReason());
                return entry;
            })
            .collect(Collectors.toList());

        try {
            return objectMapper.writeValueAsString(results);
        } catch (Exception e) {
            return "[]";
        }
    }

    private QualityGateResult parseResponse(String response) {
        try {
            var node = objectMapper.readTree(response);
            boolean passed = node.has("passed") && node.get("passed").asBoolean();
            String summary = node.has("summary") ? node.get("summary").asText() : "";

            List<String> findings = new ArrayList<>();
            if (node.has("findings") && node.get("findings").isArray()) {
                for (var finding : node.get("findings")) {
                    findings.add(finding.asText());
                }
            }

            return new QualityGateResult(passed, summary, findings);
        } catch (Exception e) {
            return new QualityGateResult(false, "Failed to parse quality gate response: " + response, List.of());
        }
    }

    private record QualityGateResult(boolean passed, String summary, List<String> findings) {}
}
