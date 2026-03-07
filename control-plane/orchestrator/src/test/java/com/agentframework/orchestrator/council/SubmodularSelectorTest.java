package com.agentframework.orchestrator.council;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SubmodularSelector} — CELF greedy submodular maximisation.
 *
 * @see <a href="https://doi.org/10.1007/BF01588971">Nemhauser, Wolsey &amp; Fisher (1978)</a>
 */
class SubmodularSelectorTest {

    private final SubmodularSelector<String> selector = new SubmodularSelector<>();

    @Test
    void select_emptyCandidates_returnsEmpty() {
        List<String> result = selector.select(List.of(), 3, (selected, candidate) -> 1.0);
        assertThat(result).isEmpty();
    }

    @Test
    void select_kGreaterThanCandidates_returnsAll() {
        Map<String, Set<String>> topics = Map.of(
                "A", Set.of("t1", "t2"),
                "B", Set.of("t3"));
        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        List<String> result = selector.select(topics.keySet(), 10, fn);

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void select_greedyMaximisesCoverage() {
        // 8 profiles with overlapping topics — selecting 3 should cover more topics
        // than a random selection of 3
        Map<String, Set<String>> topics = TopicCoverageFunction.defaultProfileTopics();
        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        List<String> result = selector.select(topics.keySet(), 3, fn);

        assertThat(result).hasSize(3);

        // Count unique topics covered by the greedy selection
        Set<String> coveredTopics = fn.coveredTopics(new LinkedHashSet<>(result));

        // Greedy should cover a significant fraction of total topics
        // (at least (1 - 1/e) ≈ 63% of the optimal for 3 elements)
        assertThat(coveredTopics.size()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void select_stopsWhenGainZero() {
        // Only 2 distinct topics — even with k=5, should select at most 2 profiles
        Map<String, Set<String>> topics = Map.of(
                "A", Set.of("t1"),
                "B", Set.of("t2"),
                "C", Set.of("t1"),      // fully overlaps with A
                "D", Set.of("t2"));     // fully overlaps with B

        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        List<String> result = selector.select(topics.keySet(), 5, fn);

        // Should select at most 2 (once both topics are covered, gain = 0)
        assertThat(result).hasSizeLessThanOrEqualTo(2);
        Set<String> covered = fn.coveredTopics(new LinkedHashSet<>(result));
        assertThat(covered).containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void select_respectsKLimit() {
        Map<String, Set<String>> topics = Map.of(
                "A", Set.of("t1", "t2"),
                "B", Set.of("t3", "t4"),
                "C", Set.of("t5", "t6"),
                "D", Set.of("t7", "t8"));
        TopicCoverageFunction fn = new TopicCoverageFunction(topics);

        List<String> result = selector.select(topics.keySet(), 2, fn);

        assertThat(result).hasSize(2);
    }
}
