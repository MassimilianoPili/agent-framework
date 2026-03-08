package com.agentframework.orchestrator.council;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TopicCoverageFunction} — submodular topic coverage for council selection.
 */
class TopicCoverageFunctionTest {

    @Test
    void marginalGain_emptySelected_returnsFullTopicCount() {
        Map<String, Set<String>> topics = Map.of(
                "A", Set.of("t1", "t2", "t3"));
        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        double gain = fn.marginalGain(Set.of(), "A");

        assertThat(gain).isEqualTo(3.0);
    }

    @Test
    void marginalGain_partialOverlap_returnsNewTopicsOnly() {
        Map<String, Set<String>> topics = Map.of(
                "A", Set.of("t1", "t2", "t3"),
                "B", Set.of("t2", "t3", "t4", "t5"));
        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        // A covers {t1, t2, t3}; B adds {t4, t5} (2 new topics)
        double gain = fn.marginalGain(Set.of("A"), "B");

        assertThat(gain).isEqualTo(2.0);
    }

    @Test
    void defaultProfileTopics_containsAllStandardProfiles() {
        Map<String, Set<String>> defaults = TopicCoverageFunction.defaultProfileTopics();

        // 5 managers + 4 specialists = 9 profiles
        assertThat(defaults).hasSize(9);
        assertThat(defaults).containsKey("be-manager");
        assertThat(defaults).containsKey("fe-manager");
        assertThat(defaults).containsKey("mobile-manager");
        assertThat(defaults).containsKey("security-manager");
        assertThat(defaults).containsKey("data-manager");
        assertThat(defaults).containsKey("database-specialist");
        assertThat(defaults).containsKey("auth-specialist");
        assertThat(defaults).containsKey("api-specialist");
        assertThat(defaults).containsKey("testing-specialist");
    }

    @Test
    void defaultProfileTopics_mobileManager_coversExpectedTopics() {
        Set<String> mobileTopics = TopicCoverageFunction.defaultProfileTopics().get("mobile-manager");

        assertThat(mobileTopics).containsExactlyInAnyOrder(
                "mobile", "ios", "android", "kotlin", "swiftui", "compose");
    }

    @Test
    void marginalGain_fullOverlap_returnsZero() {
        Map<String, Set<String>> topics = Map.of(
                "A", Set.of("t1", "t2"),
                "B", Set.of("t1", "t2"));  // identical coverage
        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        double gain = fn.marginalGain(Set.of("A"), "B");

        assertThat(gain).isEqualTo(0.0);
    }
}
