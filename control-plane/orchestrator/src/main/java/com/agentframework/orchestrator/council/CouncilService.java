package com.agentframework.orchestrator.council;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.gp.PlanDecompositionPredictor;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Orchestrates advisory council sessions for domain knowledge enrichment.
 *
 * <p>Two usage contexts:</p>
 * <ol>
 *   <li><b>Pre-planning</b>: called by {@code OrchestrationService.createAndStart()} before
 *       the planner decomposes the spec. Produces a global {@code CouncilReport} stored on
 *       {@code Plan.councilReport} and injected into the planner prompt.</li>
 *   <li><b>In-plan task</b>: called by {@code OrchestrationService.dispatchReadyItems()} when
 *       a {@code COUNCIL_MANAGER} item is encountered. Produces a task-scoped report that
 *       flows to dependent workers via dependency results.</li>
 * </ol>
 *
 * <p>Member consultation is always parallel — each MANAGER/SPECIALIST responds independently
 * to the same context, then the COUNCIL_MANAGER LLM synthesises into a {@code CouncilReport}.</p>
 */
@Service
public class CouncilService {

    private static final Logger log = LoggerFactory.getLogger(CouncilService.class);

    /** Bounded thread pool for parallel member consultations (max 8 threads, queue 20). */
    private static final ExecutorService COUNCIL_EXECUTOR =
        new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(20));

    private static final long MEMBER_CONSULTATION_TIMEOUT_SECONDS = 120;

    private final ChatClient chatClient;
    private final CouncilPromptLoader promptLoader;
    private final CouncilProperties properties;
    private final ObjectMapper objectMapper;
    private final Optional<CouncilRagEnricher> ragEnricher;
    private final Optional<PlanDecompositionPredictor> decompositionPredictor;
    private final CouncilCommitmentRepository commitmentRepository;
    private final QuadraticVotingService quadraticVotingService;

    public CouncilService(ChatClient chatClient,
                          CouncilPromptLoader promptLoader,
                          CouncilProperties properties,
                          ObjectMapper objectMapper,
                          Optional<CouncilRagEnricher> ragEnricher,
                          Optional<PlanDecompositionPredictor> decompositionPredictor,
                          CouncilCommitmentRepository commitmentRepository,
                          QuadraticVotingService quadraticVotingService) {
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.ragEnricher = ragEnricher;
        this.decompositionPredictor = decompositionPredictor;
        this.commitmentRepository = commitmentRepository;
        this.quadraticVotingService = quadraticVotingService;
    }

    @PreDestroy
    void shutdownExecutor() {
        COUNCIL_EXECUTOR.shutdown();
        try {
            if (!COUNCIL_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                COUNCIL_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            COUNCIL_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    /**
     * Pre-planning council session: consults domain experts about the full spec.
     *
     * <p>Called synchronously from {@code OrchestrationService.createAndStart()}, before
     * the planner decomposes the spec. The returned {@code CouncilReport} is stored on
     * {@code Plan.councilReport} and later injected into the planner and each worker task.</p>
     *
     * @param spec the original natural language specification
     * @return synthesised council consensus
     */
    public CouncilReport conductPrePlanningSession(UUID planId, String spec) {
        log.info("Starting pre-planning council session (maxMembers={})", properties.maxMembers());

        // RAG enrichment: augment spec with relevant codebase context before consulting members
        String enrichedSpec = ragEnricher.map(e -> e.enrichSpec(spec)).orElse(spec);

        List<String> selected = selectMembers(enrichedSpec, properties.maxMembers());
        log.info("Council selected {} members: {}", selected.size(), selected);

        Map<String, String> memberViews = consultMembersParallel(enrichedSpec, selected);

        // #46: commit-reveal — commit all member outputs, verify, extract verified views
        Map<String, String> verifiedViews = commitAndVerify(planId, "PRE_PLANNING", null, memberViews);

        // #49: Quadratic Voting aggregation (overlay on verified views)
        QuadraticVotingService.QvAggregation qvAggregation = null;
        if (properties.quadraticVotingEnabled()) {
            qvAggregation = quadraticVotingService.parseAndAggregate(
                verifiedViews, properties.baseVoiceCredits());
            log.info("QV aggregation: {} weighted recommendations, {} fallback members",
                     qvAggregation.weightedRecommendations().size(),
                     qvAggregation.fallbackMembers().size());
        }

        CouncilReport report = synthesize(enrichedSpec, verifiedViews, qvAggregation);

        // Enrich with GP taste-profile prediction (informational, does not alter LLM decisions)
        report = enrichWithGpPrediction(report, selected.size());

        log.info("Pre-planning council session complete. Decisions: {}", report.architectureDecisions());
        return report;
    }

    /**
     * Task-level council session: focused deliberation for a specific in-plan task.
     *
     * <p>Called synchronously by {@code OrchestrationService} when a {@code COUNCIL_MANAGER}
     * item is encountered during dispatch. The result is stored as the item's result JSON
     * and flows to dependent workers via the existing dependency-result mechanism.</p>
     *
     * @param taskTitle       title of the COUNCIL_MANAGER plan item
     * @param taskDescription full description of what the task needs advice on
     * @param dependencyResults JSON results from completed dependency items (taskKey → resultJson)
     * @return synthesised task-scoped council report
     */
    public CouncilReport conductTaskSession(UUID planId, String taskKey,
                                             String taskTitle,
                                             String taskDescription,
                                             Map<String, String> dependencyResults) {
        log.info("Starting task-level council session for: {}", taskTitle);

        // Build context: task description + prior dependency results
        String context = buildTaskContext(taskTitle, taskDescription, dependencyResults);

        // RAG enrichment: augment context with relevant codebase knowledge
        String enrichedContext = ragEnricher.map(e -> e.enrichSpec(context)).orElse(context);

        List<String> selected = selectMembersForTask(taskTitle, enrichedContext, properties.maxMembers());
        log.info("Task council selected {} members: {}", selected.size(), selected);

        Map<String, String> memberViews = consultMembersParallel(enrichedContext, selected);

        // #46: commit-reveal — commit all member outputs, verify, extract verified views
        Map<String, String> verifiedViews = commitAndVerify(planId, "TASK", taskKey, memberViews);

        // #49: Quadratic Voting aggregation
        QuadraticVotingService.QvAggregation qvAggregation = null;
        if (properties.quadraticVotingEnabled()) {
            qvAggregation = quadraticVotingService.parseAndAggregate(
                verifiedViews, properties.baseVoiceCredits());
        }

        CouncilReport report = synthesize(enrichedContext, verifiedViews, qvAggregation);

        log.info("Task council session complete for: {}", taskTitle);
        return report;
    }

    // ─── Private Helpers ───────────────────────────────────────────────────────

    /**
     * Calls the council-selector LLM to dynamically pick relevant members for the spec.
     *
     * <p>The selector prompt returns a JSON array of profile names:
     * e.g. {@code ["be-manager", "security-specialist", "database-specialist"]}.</p>
     */
    private List<String> selectMembers(String spec, int maxMembers) {
        if (properties.submodularSelectionEnabled()) {
            return selectMembersSubmodular(maxMembers);
        }

        String selectorPrompt = promptLoader.loadSelectorPrompt();
        String userMessage = "Specification:\n" + spec
            + "\n\nSelect up to " + maxMembers + " members from the available roster.";

        String raw = chatClient.prompt()
            .system(selectorPrompt)
            .user(userMessage)
            .call()
            .content();

        return parseStringList(raw, "council-selector");
    }

    /**
     * Variant of {@link #selectMembers} scoped to a specific task context.
     */
    private List<String> selectMembersForTask(String taskTitle, String context, int maxMembers) {
        if (properties.submodularSelectionEnabled()) {
            return selectMembersSubmodular(maxMembers);
        }

        String selectorPrompt = promptLoader.loadSelectorPrompt();
        String userMessage = "Task: " + taskTitle
            + "\n\nContext:\n" + context
            + "\n\nSelect up to " + maxMembers + " members most relevant to this task.";

        String raw = chatClient.prompt()
            .system(selectorPrompt)
            .user(userMessage)
            .call()
            .content();

        return parseStringList(raw, "council-selector-task");
    }

    /**
     * Selects council members using submodular optimisation (CELF greedy).
     * Maximises topic coverage diversity with a (1 - 1/e) ≈ 63% optimality guarantee.
     */
    private List<String> selectMembersSubmodular(int maxMembers) {
        Map<String, java.util.Set<String>> profileTopics = TopicCoverageFunction.defaultProfileTopics();
        TopicCoverageFunction fn = new TopicCoverageFunction(profileTopics);
        SubmodularSelector<String> selector = new SubmodularSelector<>();
        List<String> selected = selector.select(profileTopics.keySet(), maxMembers, fn);
        log.info("Submodular selection: {} members selected for max topic coverage", selected.size());
        return selected;
    }

    /**
     * Consults each selected member in parallel using virtual threads.
     *
     * <p>Each member receives the full context and their domain-specific system prompt.
     * Members respond independently — no member sees another's output at this stage.</p>
     *
     * @param context the shared context (spec or task description)
     * @param profiles member profile names to consult
     * @return map of profile → member view text
     */
    private Map<String, String> consultMembersParallel(String context, List<String> profiles) {
        List<CompletableFuture<Map.Entry<String, String>>> futures = profiles.stream()
            .map(profile -> CompletableFuture.supplyAsync(() -> {
                try {
                    String view = consultMember(profile, context);
                    return Map.entry(profile, view);
                } catch (Exception e) {
                    log.warn("Council member {} failed, skipping: {}", profile, e.getMessage());
                    return Map.entry(profile, "(member failed: " + e.getMessage() + ")");
                }
            }, COUNCIL_EXECUTOR))
            .toList();

        // Wait for all members with a global timeout to prevent indefinite hang
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(MEMBER_CONSULTATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Council consultation timed out after {}s, proceeding with completed members",
                     MEMBER_CONSULTATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("Council consultation interrupted: {}", e.getMessage());
        }

        // Collect completed results, use fallback for timed-out members
        Map<String, String> results = new LinkedHashMap<>();
        for (int i = 0; i < profiles.size(); i++) {
            String profile = profiles.get(i);
            CompletableFuture<Map.Entry<String, String>> future = futures.get(i);
            if (future.isDone() && !future.isCompletedExceptionally()) {
                results.put(profile, future.join().getValue());
            } else {
                log.warn("Council member {} did not complete in time, using fallback", profile);
                results.put(profile, "(member timed out)");
            }
        }
        return results;
    }

    /**
     * Calls the LLM for a single council member with their domain-specific system prompt.
     */
    private String consultMember(String profile, String context) {
        String memberPrompt = promptLoader.loadMemberPrompt(profile);

        // #49: append QV instructions when Quadratic Voting is enabled
        if (properties.quadraticVotingEnabled()) {
            String qvSuffix = promptLoader.loadQvSuffix()
                .replace("{voiceCredits}", String.valueOf(properties.baseVoiceCredits()));
            memberPrompt = memberPrompt + "\n\n" + qvSuffix;
        }

        log.debug("Consulting council member: {}{}", profile,
                  properties.quadraticVotingEnabled() ? " (QV enabled)" : "");

        return chatClient.prompt()
            .system(memberPrompt)
            .user("Context to advise on:\n\n" + context)
            .call()
            .content();
    }

    /**
     * Calls the COUNCIL_MANAGER LLM to synthesise all member views into a {@link CouncilReport}.
     *
     * @param context      enriched spec or task context
     * @param memberViews  verified member outputs (profile → raw text)
     * @param qvAggregation QV aggregation results, or null if QV is disabled
     */
    private CouncilReport synthesize(String context, Map<String, String> memberViews,
                                     QuadraticVotingService.QvAggregation qvAggregation) {
        BeanOutputConverter<CouncilReport> converter = new BeanOutputConverter<>(CouncilReport.class);

        StringBuilder memberViewsText = new StringBuilder();
        memberViews.forEach((profile, view) ->
            memberViewsText.append("### ").append(profile).append("\n").append(view).append("\n\n"));

        String userMessage = "Original context:\n" + context
            + "\n\n## Member Views\n\n" + memberViewsText;

        // #49: inject weighted recommendations into synthesis prompt
        if (qvAggregation != null && !qvAggregation.weightedRecommendations().isEmpty()) {
            userMessage += "\n\n" + quadraticVotingService.formatForSynthesis(qvAggregation);
        }

        userMessage += "\n\n" + converter.getFormat();

        String raw = chatClient.prompt()
            .system(promptLoader.loadManagerPrompt())
            .user(userMessage)
            .call()
            .content();

        String rawJson = com.agentframework.orchestrator.planner.PlannerService.stripMarkdownFences(raw);
        CouncilReport report = converter.convert(rawJson);
        if (report == null) {
            log.warn("Council synthesizer returned null, using empty report");
            return new CouncilReport(
                new ArrayList<>(memberViews.keySet()),
                List.of(), null, List.of(), null, null, null,
                memberViews,
                null, null, null,  // taste-profile: null on fallback
                qvAggregation != null ? qvAggregation.weightedRecommendations() : null
            );
        }

        // Attach QV weighted recommendations to the report
        if (qvAggregation != null) {
            report = new CouncilReport(
                report.selectedMembers(), report.architectureDecisions(),
                report.techStackRationale(), report.securityConsiderations(),
                report.dataModelingGuidelines(), report.apiDesignGuidelines(),
                report.testingStrategy(), report.memberInsights(),
                report.predictedReward(), report.predictionUncertainty(),
                report.decompositionHint(),
                qvAggregation.weightedRecommendations());
        }
        return report;
    }

    /**
     * Enriches a synthesised {@link CouncilReport} with GP taste-profile prediction.
     *
     * <p>Uses default structural estimates for the pre-planning stage (the exact plan
     * structure is not yet known). If the predictor is absent or in cold-start mode,
     * returns the report unmodified.</p>
     *
     * @param report      report from LLM synthesis
     * @param nMembers    number of council members selected (used as proxy for nTasks estimate)
     */
    private CouncilReport enrichWithGpPrediction(CouncilReport report, int nMembers) {
        if (decompositionPredictor.isEmpty()) return report;

        // Estimate plan structure at pre-planning time: use member count as nTasks proxy,
        // assume context and review tasks are likely for non-trivial specs.
        int estimatedTasks = Math.max(3, nMembers + 2);

        return decompositionPredictor.get()
                .predict(estimatedTasks, true, true, nMembers / 2, Math.max(0, nMembers / 4))
                .map(pred -> {
                    double mu     = pred.mu();
                    double sigma  = Math.sqrt(Math.max(0.0, pred.sigma2()));
                    String hint   = String.format(
                            "GP: predicted reward %.2f ± %.2f for a %d-task plan",
                            mu, sigma, estimatedTasks);
                    log.debug("GP taste-profile: {}", hint);
                    return new CouncilReport(
                            report.selectedMembers(), report.architectureDecisions(),
                            report.techStackRationale(), report.securityConsiderations(),
                            report.dataModelingGuidelines(), report.apiDesignGuidelines(),
                            report.testingStrategy(), report.memberInsights(),
                            mu, pred.sigma2(), hint,
                            report.weightedRecommendations());
                })
                .orElse(report);  // cold start: return original report unchanged
    }

    /**
     * Commit-reveal: commits all member outputs, verifies integrity, returns only verified views (#46).
     *
     * <p>Any member whose hash verification fails is excluded from the synthesis input
     * and a SECURITY WARNING is logged. This prevents tampered outputs from influencing
     * the council report.</p>
     */
    private Map<String, String> commitAndVerify(UUID planId, String sessionType, String taskKey,
                                                 Map<String, String> memberViews) {
        List<CouncilCommitment> commitments = memberViews.entrySet().stream()
                .map(e -> CouncilCommitment.create(planId, sessionType, taskKey, e.getKey(), e.getValue()))
                .toList();
        commitmentRepository.saveAll(commitments);

        Map<String, String> verifiedViews = new LinkedHashMap<>();
        for (CouncilCommitment commitment : commitments) {
            if (commitment.verify()) {
                verifiedViews.put(commitment.getMemberProfile(), commitment.getRawOutput());
            } else {
                log.error("SECURITY WARNING: Council commitment verification failed for member={} plan={} session={}",
                        commitment.getMemberProfile(), planId, sessionType);
            }
        }
        commitmentRepository.saveAll(commitments); // persist verified/verificationFailed flags

        if (verifiedViews.size() < memberViews.size()) {
            log.warn("Council commit-reveal: {} of {} members failed verification (plan={}, session={})",
                    memberViews.size() - verifiedViews.size(), memberViews.size(), planId, sessionType);
        }

        return verifiedViews;
    }

    /**
     * Builds a combined context string for task-level sessions, including dependency results.
     */
    private String buildTaskContext(String title, String description,
                                    Map<String, String> dependencyResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Task: ").append(title).append("\n\n");
        sb.append(description).append("\n\n");
        if (!dependencyResults.isEmpty()) {
            sb.append("## Dependency Results\n\n");
            dependencyResults.forEach((key, result) ->
                sb.append("### ").append(key).append("\n```json\n").append(result).append("\n```\n\n"));
        }
        return sb.toString();
    }

    /**
     * Parses a JSON array of strings from a raw LLM response.
     * Falls back to a default set if parsing fails.
     */
    private List<String> parseStringList(String raw, String context) {
        try {
            // Strip any markdown code blocks the LLM might wrap around the JSON
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
            }
            return objectMapper.readValue(cleaned, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Could not parse member list from {}, using defaults. Raw: {}", context, raw);
            return List.of("be-manager", "security-specialist");
        }
    }
}
