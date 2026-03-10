package com.agentframework.orchestrator.council;

import com.agentframework.orchestrator.council.QuadraticVotingService.MemberVoteAllocation;
import com.agentframework.orchestrator.council.QuadraticVotingService.QvAggregation;
import com.agentframework.orchestrator.council.QuadraticVotingService.Recommendation;
import com.agentframework.orchestrator.council.QuadraticVotingService.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuadraticVotingServiceTest {

    private QuadraticVotingService service;

    @BeforeEach
    void setUp() {
        service = new QuadraticVotingService();
    }

    // ── Voice Credits ──────────────────────────────────────────────────────────

    @Nested
    class VoiceCredits {

        @Test
        void defaultElo_returns100() {
            assertThat(service.computeVoiceCredits(1600.0, 100)).isEqualTo(100);
        }

        @Test
        void highElo_cappedAt160() {
            assertThat(service.computeVoiceCredits(2200.0, 100)).isEqualTo(160);
        }

        @Test
        void lowElo_clampedAt70() {
            assertThat(service.computeVoiceCredits(1200.0, 100)).isEqualTo(70);
        }

        @Test
        void eloIncrement_adds10Per100() {
            // 1800 → base + floor((1800-1600)/100)*10 = 100 + 20 = 120
            assertThat(service.computeVoiceCredits(1800.0, 100)).isEqualTo(120);
        }

        @Test
        void eloBelow1600_reduces() {
            // 1500 → base + floor((1500-1600)/100)*10 = 100 + (-10) = 90
            assertThat(service.computeVoiceCredits(1500.0, 100)).isEqualTo(90);
        }

        @Test
        void exactlyAt1600_equalsBase() {
            assertThat(service.computeVoiceCredits(1600.0, 80)).isEqualTo(80);
        }

        @Test
        void fractionalElo_floored() {
            // 1750 → floor((1750-1600)/100)*10 = floor(1.5)*10 = 10
            assertThat(service.computeVoiceCredits(1750.0, 100)).isEqualTo(110);
        }
    }

    // ── Budget Validation ──────────────────────────────────────────────────────

    @Nested
    class BudgetValidation {

        @Test
        void withinBudget_valid() {
            // 8² + 6² = 64 + 36 = 100
            MemberVoteAllocation alloc = new MemberVoteAllocation("be-manager", "analysis",
                List.of(
                    new Recommendation("R1", "Use bcrypt", 8, "critical"),
                    new Recommendation("R2", "Rate limiting", 6, "important")),
                100);
            ValidationResult result = service.validateBudget(alloc, 100);
            assertThat(result.valid()).isTrue();
            assertThat(result.actualCost()).isEqualTo(100);
        }

        @Test
        void overBudget_invalid() {
            // 9² + 6² = 81 + 36 = 117
            MemberVoteAllocation alloc = new MemberVoteAllocation("be-manager", "analysis",
                List.of(
                    new Recommendation("R1", "Use bcrypt", 9, "critical"),
                    new Recommendation("R2", "Rate limiting", 6, "important")),
                117);
            ValidationResult result = service.validateBudget(alloc, 100);
            assertThat(result.valid()).isFalse();
            assertThat(result.actualCost()).isEqualTo(117);
        }

        @Test
        void exactlyOnBudget_valid() {
            // 10² = 100
            MemberVoteAllocation alloc = new MemberVoteAllocation("be-manager", "analysis",
                List.of(new Recommendation("R1", "All-in", 10, "critical")),
                100);
            assertThat(service.validateBudget(alloc, 100).valid()).isTrue();
        }

        @Test
        void emptyRecommendations_valid() {
            MemberVoteAllocation alloc = new MemberVoteAllocation("be-manager", "analysis",
                List.of(), 0);
            assertThat(service.validateBudget(alloc, 100).valid()).isTrue();
        }
    }

    // ── Aggregation ────────────────────────────────────────────────────────────

    @Nested
    class Aggregation {

        @Test
        void singleMember_sortedByVotesDesc() {
            MemberVoteAllocation alloc = new MemberVoteAllocation("be-manager", "analysis",
                List.of(
                    new Recommendation("R1", "Bcrypt", 3, ""),
                    new Recommendation("R2", "Rate limit", 8, "")),
                73);
            List<WeightedRecommendation> result = service.aggregate(List.of(alloc));
            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo("R2");
            assertThat(result.get(0).totalVotes()).isEqualTo(8);
            assertThat(result.get(1).id()).isEqualTo("R1");
            assertThat(result.get(1).totalVotes()).isEqualTo(3);
        }

        @Test
        void multipleMembers_mergesById() {
            MemberVoteAllocation alloc1 = new MemberVoteAllocation("be-manager", "",
                List.of(
                    new Recommendation("R1", "Bcrypt", 8, ""),
                    new Recommendation("R2", "Rate limit", 6, "")),
                100);
            MemberVoteAllocation alloc2 = new MemberVoteAllocation("security-specialist", "",
                List.of(
                    new Recommendation("R1", "Bcrypt cost=12", 7, ""),
                    new Recommendation("R3", "OWASP checks", 5, "")),
                74);

            List<WeightedRecommendation> result = service.aggregate(List.of(alloc1, alloc2));

            // R1: 8+7=15, R2: 6, R3: 5
            assertThat(result).hasSize(3);
            assertThat(result.get(0).id()).isEqualTo("R1");
            assertThat(result.get(0).totalVotes()).isEqualTo(15);
            assertThat(result.get(0).voters()).containsExactly("be-manager", "security-specialist");
            assertThat(result.get(1).id()).isEqualTo("R2");
            assertThat(result.get(1).totalVotes()).isEqualTo(6);
            assertThat(result.get(2).id()).isEqualTo("R3");
        }

        @Test
        void differentIds_noDuplication() {
            MemberVoteAllocation alloc1 = new MemberVoteAllocation("be-manager", "",
                List.of(new Recommendation("R1", "Hexagonal", 5, "")), 25);
            MemberVoteAllocation alloc2 = new MemberVoteAllocation("fe-manager", "",
                List.of(new Recommendation("R2", "React SSR", 5, "")), 25);

            List<WeightedRecommendation> result = service.aggregate(List.of(alloc1, alloc2));
            assertThat(result).hasSize(2);
            assertThat(result.get(0).totalVotes()).isEqualTo(5);
            assertThat(result.get(1).totalVotes()).isEqualTo(5);
        }
    }

    // ── JSON Extraction ────────────────────────────────────────────────────────

    @Nested
    class JsonExtraction {

        @Test
        void pureJson_extractedDirectly() {
            String json = "{\"analysis\":\"test\",\"recommendations\":[],\"voiceCreditsUsed\":0}";
            assertThat(service.extractJson(json)).isEqualTo(json);
        }

        @Test
        void jsonInCodeFence_extracted() {
            String raw = "Here is my analysis:\n```json\n{\"analysis\":\"test\",\"recommendations\":[{\"id\":\"R1\",\"text\":\"bcrypt\",\"votesAllocated\":5,\"rationale\":\"security\"}],\"voiceCreditsUsed\":25}\n```\nHope this helps!";
            String extracted = service.extractJson(raw);
            assertThat(extracted).startsWith("{\"analysis\":");
            assertThat(extracted).contains("\"recommendations\"");
        }

        @Test
        void nullInput_returnsNull() {
            assertThat(service.extractJson(null)).isNull();
        }

        @Test
        void blankInput_returnsNull() {
            assertThat(service.extractJson("   ")).isNull();
        }

        @Test
        void noJson_returnsNull() {
            assertThat(service.extractJson("I think we should use bcrypt for passwords.")).isNull();
        }
    }

    // ── Parse and Aggregate (integration) ──────────────────────────────────────

    @Nested
    class ParseAndAggregate {

        @Test
        void validJson_parsesCorrectly() {
            String json = """
                {
                  "analysis": "Backend analysis",
                  "recommendations": [
                    {"id": "R1", "text": "Use bcrypt", "votesAllocated": 8, "rationale": "security"},
                    {"id": "R2", "text": "Rate limiting", "votesAllocated": 6, "rationale": "protection"}
                  ],
                  "voiceCreditsUsed": 100
                }
                """;
            Map<String, String> views = new LinkedHashMap<>();
            views.put("be-manager", json);

            QvAggregation result = service.parseAndAggregate(views, 100);

            assertThat(result.fallbackMembers()).isEmpty();
            assertThat(result.memberAllocations()).containsKey("be-manager");
            assertThat(result.weightedRecommendations()).hasSize(2);
            assertThat(result.weightedRecommendations().get(0).totalVotes()).isEqualTo(8);
        }

        @Test
        void jsonInCodeBlock_parsed() {
            String raw = "Here's my advice:\n```json\n" +
                "{\"analysis\":\"test\",\"recommendations\":[" +
                "{\"id\":\"R1\",\"text\":\"bcrypt\",\"votesAllocated\":10,\"rationale\":\"sec\"}]," +
                "\"voiceCreditsUsed\":100}\n```";
            Map<String, String> views = Map.of("security-specialist", raw);

            QvAggregation result = service.parseAndAggregate(views, 100);

            assertThat(result.fallbackMembers()).isEmpty();
            assertThat(result.weightedRecommendations()).hasSize(1);
        }

        @Test
        void invalidJson_addedToFallback() {
            Map<String, String> views = Map.of(
                "be-manager", "I recommend using hexagonal architecture and PostgreSQL.");

            QvAggregation result = service.parseAndAggregate(views, 100);

            assertThat(result.fallbackMembers()).containsExactly("be-manager");
            assertThat(result.memberAllocations()).isEmpty();
            assertThat(result.weightedRecommendations()).isEmpty();
        }

        @Test
        void mixedValidAndInvalid_partialResult() {
            String validJson = """
                {"analysis":"good","recommendations":[
                  {"id":"R1","text":"bcrypt","votesAllocated":7,"rationale":"sec"}
                ],"voiceCreditsUsed":49}
                """;
            Map<String, String> views = new LinkedHashMap<>();
            views.put("security-specialist", validJson);
            views.put("be-manager", "Plain text advice without JSON");

            QvAggregation result = service.parseAndAggregate(views, 100);

            assertThat(result.memberAllocations()).hasSize(1);
            assertThat(result.fallbackMembers()).containsExactly("be-manager");
            assertThat(result.weightedRecommendations()).hasSize(1);
        }

        @Test
        void overBudget_slightlyOverspent_scalesDown() {
            // 9² + 7² = 81 + 49 = 130, budget = 100, ratio = 1.3 <= 1.5 → scale down
            String json = """
                {"analysis":"test","recommendations":[
                  {"id":"R1","text":"bcrypt","votesAllocated":9,"rationale":""},
                  {"id":"R2","text":"rate limit","votesAllocated":7,"rationale":""}
                ],"voiceCreditsUsed":130}
                """;
            Map<String, String> views = Map.of("be-manager", json);

            QvAggregation result = service.parseAndAggregate(views, 100);

            // Should be scaled, not in fallback
            assertThat(result.fallbackMembers()).isEmpty();
            assertThat(result.memberAllocations()).containsKey("be-manager");
            // Votes should be reduced
            MemberVoteAllocation alloc = result.memberAllocations().get("be-manager");
            long cost = alloc.recommendations().stream()
                .mapToLong(r -> (long) r.votesAllocated() * r.votesAllocated()).sum();
            assertThat(cost).isLessThanOrEqualTo(100);
        }

        @Test
        void grosslyOverBudget_fallback() {
            // 10² + 10² = 200, budget = 100, ratio = 2.0 > 1.5 → fallback
            String json = """
                {"analysis":"test","recommendations":[
                  {"id":"R1","text":"bcrypt","votesAllocated":10,"rationale":""},
                  {"id":"R2","text":"rate limit","votesAllocated":10,"rationale":""}
                ],"voiceCreditsUsed":200}
                """;
            Map<String, String> views = Map.of("be-manager", json);

            QvAggregation result = service.parseAndAggregate(views, 100);

            assertThat(result.fallbackMembers()).containsExactly("be-manager");
            assertThat(result.memberAllocations()).isEmpty();
        }

        @Test
        void emptyViews_emptyResult() {
            QvAggregation result = service.parseAndAggregate(Map.of(), 100);

            assertThat(result.weightedRecommendations()).isEmpty();
            assertThat(result.memberAllocations()).isEmpty();
            assertThat(result.fallbackMembers()).isEmpty();
        }
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    @Nested
    class Formatting {

        @Test
        void formatForSynthesis_producesReadableMarkdown() {
            QvAggregation agg = new QvAggregation(
                List.of(
                    new WeightedRecommendation("R1", "Use bcrypt cost=12", 14,
                        List.of("be-manager", "security-specialist")),
                    new WeightedRecommendation("R2", "Rate limiting", 6,
                        List.of("security-specialist"))),
                Map.of(),
                List.of("data-manager"));

            String formatted = service.formatForSynthesis(agg);

            assertThat(formatted).contains("## Weighted Recommendations (Quadratic Voting)");
            assertThat(formatted).contains("[14 votes]");
            assertThat(formatted).contains("Use bcrypt cost=12");
            assertThat(formatted).contains("be-manager, security-specialist");
            assertThat(formatted).contains("[6 votes]");
            assertThat(formatted).contains("data-manager");
        }

        @Test
        void formatForSynthesis_noFallback_omitsFallbackLine() {
            QvAggregation agg = new QvAggregation(
                List.of(new WeightedRecommendation("R1", "bcrypt", 10, List.of("sec"))),
                Map.of(), List.of());

            String formatted = service.formatForSynthesis(agg);

            assertThat(formatted).doesNotContain("unstructured output");
        }
    }
}
