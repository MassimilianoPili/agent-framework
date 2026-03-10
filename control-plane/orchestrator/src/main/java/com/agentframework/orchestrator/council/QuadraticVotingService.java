package com.agentframework.orchestrator.council;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Quadratic Voting service for council member recommendation weighting (#49).
 *
 * <p>Each council member receives a voice credit budget. Allocating {@code k} votes
 * to a recommendation costs {@code k²} credits. This quadratic cost forces members
 * to express <em>intensity</em> of preference — concentrating votes is expensive,
 * incentivising diversification across recommendations.</p>
 *
 * <p>The service is stateless and performs three functions:</p>
 * <ol>
 *   <li>Compute voice credit budgets (ELO-aware, with default fallback)</li>
 *   <li>Parse and validate QV allocations from raw LLM member outputs</li>
 *   <li>Aggregate weighted recommendations across all members</li>
 * </ol>
 *
 * @see <a href="https://vitalik.eth.limo/general/2019/12/07/quadratic.html">
 *      Quadratic Payments: A Primer (Vitalik Buterin, 2019)</a>
 */
@Service
public class QuadraticVotingService {

    private static final Logger log = LoggerFactory.getLogger(QuadraticVotingService.class);

    /** Regex to extract JSON from markdown code fences: ```json ... ``` */
    private static final Pattern JSON_FENCE_PATTERN =
        Pattern.compile("```json\\s*\\n(\\{.*?\\})\\s*```", Pattern.DOTALL);

    /** Regex to extract a top-level JSON object containing "recommendations" */
    private static final Pattern JSON_OBJECT_PATTERN =
        Pattern.compile("(\\{[^{}]*\"recommendations\"\\s*:\\s*\\[.*?\\]\\s*[^{}]*\\})", Pattern.DOTALL);

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // ── Records ────────────────────────────────────────────────────────────────

    public record Recommendation(String id, String text, int votesAllocated, String rationale) {}

    public record MemberVoteAllocation(
        String memberProfile,
        String analysis,
        List<Recommendation> recommendations,
        int voiceCreditsUsed
    ) {}

    public record QvAggregation(
        List<WeightedRecommendation> weightedRecommendations,
        Map<String, MemberVoteAllocation> memberAllocations,
        List<String> fallbackMembers
    ) {}

    public record ValidationResult(boolean valid, long actualCost, int budget) {}

    // Used for Jackson deserialization of member LLM output
    private record RawMemberOutput(
        String analysis,
        List<Recommendation> recommendations,
        int voiceCreditsUsed
    ) {}

    // ── Voice Credits ──────────────────────────────────────────────────────────

    /**
     * Computes voice credits for a council member based on ELO rating.
     *
     * <p>Formula: {@code base + floor((elo - 1600) / 100) * 10}, clamped to [70, 160].</p>
     *
     * @param eloRating  member's ELO rating (1600 = unrated baseline)
     * @param baseCredits base voice credits (typically 100)
     * @return voice credit budget, clamped to [70, 160]
     */
    public int computeVoiceCredits(double eloRating, int baseCredits) {
        int bonus = (int) Math.floor((eloRating - 1600.0) / 100.0) * 10;
        return Math.max(70, Math.min(160, baseCredits + bonus));
    }

    // ── Budget Validation ──────────────────────────────────────────────────────

    /**
     * Validates that a member's vote allocation fits within their credit budget.
     *
     * @param allocation the member's vote allocation
     * @param credits    available voice credits
     * @return validation result with actual cost
     */
    public ValidationResult validateBudget(MemberVoteAllocation allocation, int credits) {
        long cost = allocation.recommendations().stream()
            .mapToLong(r -> (long) r.votesAllocated() * r.votesAllocated())
            .sum();
        return new ValidationResult(cost <= credits, cost, credits);
    }

    // ── Parse + Aggregate (main entry point) ───────────────────────────────────

    /**
     * Parses QV allocations from verified member views and aggregates recommendations.
     *
     * <p>For each member view, attempts to extract and parse a JSON QV allocation.
     * Members whose output cannot be parsed are added to {@code fallbackMembers} —
     * their raw text is still passed to synthesis but without weighted votes.</p>
     *
     * @param verifiedViews member profile → raw LLM output (post commit-reveal)
     * @param baseVoiceCredits default voice credit budget per member
     * @return aggregated QV results
     */
    public QvAggregation parseAndAggregate(Map<String, String> verifiedViews, int baseVoiceCredits) {
        Map<String, MemberVoteAllocation> allocations = new LinkedHashMap<>();
        List<String> fallbackMembers = new ArrayList<>();

        for (var entry : verifiedViews.entrySet()) {
            String profile = entry.getKey();
            String rawOutput = entry.getValue();

            try {
                String json = extractJson(rawOutput);
                if (json == null) {
                    log.warn("QV: no JSON found in output of member {}, treating as fallback", profile);
                    fallbackMembers.add(profile);
                    continue;
                }

                RawMemberOutput parsed = LENIENT_MAPPER.readValue(json, RawMemberOutput.class);
                if (parsed.recommendations() == null || parsed.recommendations().isEmpty()) {
                    log.warn("QV: member {} returned empty recommendations, treating as fallback", profile);
                    fallbackMembers.add(profile);
                    continue;
                }

                MemberVoteAllocation allocation = new MemberVoteAllocation(
                    profile, parsed.analysis(), parsed.recommendations(), parsed.voiceCreditsUsed());

                // Validate budget
                ValidationResult validation = validateBudget(allocation, baseVoiceCredits);
                if (!validation.valid()) {
                    if (validation.actualCost() <= (long) (baseVoiceCredits * 1.5)) {
                        // Slightly overspent — scale down proportionally
                        allocation = scaleDown(allocation, baseVoiceCredits);
                        log.info("QV: member {} overspent ({}/{}), votes scaled down",
                                 profile, validation.actualCost(), baseVoiceCredits);
                    } else {
                        // Grossly overspent — treat as fallback
                        log.warn("QV: member {} grossly overspent ({}/{}), treating as fallback",
                                 profile, validation.actualCost(), baseVoiceCredits);
                        fallbackMembers.add(profile);
                        continue;
                    }
                }

                allocations.put(profile, allocation);
            } catch (JsonProcessingException e) {
                log.warn("QV: failed to parse JSON from member {}: {}", profile, e.getMessage());
                fallbackMembers.add(profile);
            }
        }

        List<WeightedRecommendation> weighted = aggregate(new ArrayList<>(allocations.values()));

        return new QvAggregation(weighted, allocations, fallbackMembers);
    }

    // ── Aggregation ────────────────────────────────────────────────────────────

    /**
     * Aggregates vote allocations across multiple members by recommendation ID.
     *
     * <p>Recommendations with the same {@code id} are merged: votes are summed
     * and voter profiles collected. The result is sorted by total votes descending.</p>
     */
    public List<WeightedRecommendation> aggregate(List<MemberVoteAllocation> allocations) {
        // Map: recommendation id → (text, totalVotes, voters)
        Map<String, String> textById = new LinkedHashMap<>();
        Map<String, Integer> votesById = new LinkedHashMap<>();
        Map<String, List<String>> votersById = new LinkedHashMap<>();

        for (var alloc : allocations) {
            for (var rec : alloc.recommendations()) {
                String id = rec.id() != null ? rec.id().trim() : rec.text().trim().toLowerCase();
                textById.putIfAbsent(id, rec.text());
                votesById.merge(id, rec.votesAllocated(), Integer::sum);
                votersById.computeIfAbsent(id, k -> new ArrayList<>()).add(alloc.memberProfile());
            }
        }

        return votesById.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .map(e -> new WeightedRecommendation(
                e.getKey(), textById.get(e.getKey()), e.getValue(), votersById.get(e.getKey())))
            .toList();
    }

    // ── Synthesis Formatting ───────────────────────────────────────────────────

    /**
     * Formats QV aggregation results as markdown for injection into the synthesis prompt.
     */
    public String formatForSynthesis(QvAggregation aggregation) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Weighted Recommendations (Quadratic Voting)\n\n");
        sb.append("Recommendations are ordered by aggregate vote strength. ");
        sb.append("Higher votes indicate stronger conviction from council members.\n\n");

        int rank = 1;
        for (var rec : aggregation.weightedRecommendations()) {
            String voters = String.join(", ", rec.voters());
            sb.append(String.format("%d. **[%d votes]** %s _(voted by: %s)_\n",
                                    rank++, rec.totalVotes(), rec.text(), voters));
        }

        if (!aggregation.fallbackMembers().isEmpty()) {
            sb.append("\n_Members with unstructured output (equal weight): ");
            sb.append(String.join(", ", aggregation.fallbackMembers()));
            sb.append("_\n");
        }

        return sb.toString();
    }

    // ── Private Helpers ────────────────────────────────────────────────────────

    /**
     * Attempts to extract a JSON object from raw LLM output using multiple strategies.
     */
    String extractJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return null;

        String trimmed = rawOutput.trim();

        // Strategy 1: entire output is valid JSON
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }

        // Strategy 2: JSON in markdown code fence
        Matcher fenceMatcher = JSON_FENCE_PATTERN.matcher(rawOutput);
        if (fenceMatcher.find()) {
            return fenceMatcher.group(1).trim();
        }

        // Strategy 3: embedded JSON object with "recommendations" key
        Matcher objectMatcher = JSON_OBJECT_PATTERN.matcher(rawOutput);
        if (objectMatcher.find()) {
            return objectMatcher.group(1).trim();
        }

        return null;
    }

    /**
     * Scales down vote allocations proportionally to fit within the credit budget.
     */
    private MemberVoteAllocation scaleDown(MemberVoteAllocation allocation, int credits) {
        long actualCost = allocation.recommendations().stream()
            .mapToLong(r -> (long) r.votesAllocated() * r.votesAllocated())
            .sum();

        if (actualCost <= credits) return allocation;

        // Scale factor: sqrt(credits / actualCost) applied to each vote count
        double scale = Math.sqrt((double) credits / actualCost);

        List<Recommendation> scaled = allocation.recommendations().stream()
            .map(r -> {
                int newVotes = Math.max(1, (int) (r.votesAllocated() * scale));
                return new Recommendation(r.id(), r.text(), newVotes, r.rationale());
            })
            .toList();

        int newCost = scaled.stream().mapToInt(r -> r.votesAllocated() * r.votesAllocated()).sum();
        return new MemberVoteAllocation(allocation.memberProfile(), allocation.analysis(), scaled, newCost);
    }
}
