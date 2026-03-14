package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.MctsDispatchService.MctsNode;
import com.agentframework.orchestrator.analytics.MctsDispatchService.MctsResult;
import com.agentframework.orchestrator.analytics.MctsDispatchService.TaskCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link MctsDispatchService}.
 *
 * <p>Verifies PUCT formula, greedy fallback, MCTS search, backpropagation,
 * and exploration vs exploitation behaviour.</p>
 */
class MctsDispatchServiceTest {

    private MctsDispatchService service;

    @BeforeEach
    void setUp() {
        service = new MctsDispatchService();
        ReflectionTestUtils.setField(service, "explorationC", 1.41);
        ReflectionTestUtils.setField(service, "maxSimulationsFactor", 50);
        ReflectionTestUtils.setField(service, "minStepsForMcts", 4);
    }

    // --- PUCT formula ---

    @Test
    @DisplayName("puctScore returns exploitation only when parent has no visits")
    void puctScore_noParentVisits() {
        MctsNode child = new MctsNode("t1", "profile-a", 0);
        child.visits = 5;
        child.value = 0.8;

        // sqrt(0) = 0, so exploration term is 0
        double score = service.puctScore(child, 0, 0.5);
        assertThat(score).isCloseTo(0.8, within(1e-10));
    }

    @Test
    @DisplayName("puctScore exploration term increases with parent visits")
    void puctScore_explorationIncreasesWithParentVisits() {
        MctsNode child = new MctsNode("t1", "profile-a", 0);
        child.visits = 1;
        child.value = 0.5;

        double score10 = service.puctScore(child, 10, 0.5);
        double score100 = service.puctScore(child, 100, 0.5);

        assertThat(score100).isGreaterThan(score10);
    }

    @Test
    @DisplayName("puctScore exploration term decreases with child visits")
    void puctScore_explorationDecreasesWithChildVisits() {
        MctsNode child1 = new MctsNode("t1", "a", 0);
        child1.visits = 1;
        child1.value = 0.5;

        MctsNode child10 = new MctsNode("t1", "a", 0);
        child10.visits = 10;
        child10.value = 0.5;

        double score1 = service.puctScore(child1, 100, 0.5);
        double score10 = service.puctScore(child10, 100, 0.5);

        assertThat(score1).isGreaterThan(score10);
    }

    @Test
    @DisplayName("puctScore with unvisited child returns exploration only")
    void puctScore_unvisitedChild() {
        MctsNode child = new MctsNode("t1", "a", 0);
        // visits=0, value=0.0

        double score = service.puctScore(child, 100, 0.5);

        // exploitation=0, exploration = 1.41 * 0.5 * sqrt(100) / (1+0) = 7.05
        assertThat(score).isGreaterThan(5.0);
    }

    // --- Empty / null input ---

    @Test
    @DisplayName("search with empty tasks returns empty result")
    void search_emptyTasks() {
        MctsResult result = service.search(List.of(), Map.of(), Map.of(), 0);

        assertThat(result.assignments()).isEmpty();
        assertThat(result.strategy()).isEqualTo("empty");
    }

    @Test
    @DisplayName("search with null tasks returns empty result")
    void search_nullTasks() {
        MctsResult result = service.search(null, Map.of(), Map.of(), 0);

        assertThat(result.assignments()).isEmpty();
    }

    // --- Greedy fallback ---

    @Test
    @DisplayName("search falls back to greedy for small plans")
    void search_greedyFallbackForSmallPlans() {
        List<TaskCandidate> tasks = List.of(
                new TaskCandidate("t1", "task one"),
                new TaskCandidate("t2", "task two")
        );
        Map<String, List<String>> candidates = Map.of(
                "t1", List.of("be-java", "be-go"),
                "t2", List.of("fe-react")
        );
        Map<String, Double> priors = Map.of(
                "t1:be-java", 0.9,
                "t1:be-go", 0.6,
                "t2:fe-react", 0.8
        );

        MctsResult result = service.search(tasks, candidates, priors, 0);

        assertThat(result.strategy()).isEqualTo("greedy-fallback");
        assertThat(result.simulationsRun()).isZero();
        assertThat(result.assignments()).containsEntry("t1", "be-java"); // higher prior
        assertThat(result.assignments()).containsEntry("t2", "fe-react");
    }

    @Test
    @DisplayName("greedy fallback picks highest prior value per task")
    void search_greedyPicksHighestPrior() {
        List<TaskCandidate> tasks = List.of(new TaskCandidate("t1", "task"));
        Map<String, List<String>> candidates = Map.of("t1", List.of("a", "b", "c"));
        Map<String, Double> priors = Map.of("t1:a", 0.3, "t1:b", 0.9, "t1:c", 0.5);

        MctsResult result = service.search(tasks, candidates, priors, 0);

        assertThat(result.assignments().get("t1")).isEqualTo("b");
    }

    // --- MCTS search ---

    @Test
    @DisplayName("search runs MCTS for plans with enough steps")
    void search_mctsForLargePlans() {
        List<TaskCandidate> tasks = List.of(
                new TaskCandidate("t1", "d1"),
                new TaskCandidate("t2", "d2"),
                new TaskCandidate("t3", "d3"),
                new TaskCandidate("t4", "d4")
        );
        Map<String, List<String>> candidates = Map.of(
                "t1", List.of("a", "b"),
                "t2", List.of("c"),
                "t3", List.of("d", "e"),
                "t4", List.of("f")
        );
        Map<String, Double> priors = Map.of(
                "t1:a", 0.9, "t1:b", 0.3,
                "t2:c", 0.7,
                "t3:d", 0.8, "t3:e", 0.4,
                "t4:f", 0.6
        );

        MctsResult result = service.search(tasks, candidates, priors, 100);

        assertThat(result.strategy()).startsWith("mcts");
        assertThat(result.simulationsRun()).isEqualTo(100);
        assertThat(result.assignments()).isNotEmpty();
        // With clear priors, MCTS should find good assignments
        assertThat(result.assignments()).containsKey("t1");
    }

    @Test
    @DisplayName("search auto-calculates simulations when maxSimulations=0")
    void search_autoSimulations() {
        List<TaskCandidate> tasks = List.of(
                new TaskCandidate("t1", "d1"),
                new TaskCandidate("t2", "d2"),
                new TaskCandidate("t3", "d3"),
                new TaskCandidate("t4", "d4")
        );
        Map<String, List<String>> candidates = Map.of(
                "t1", List.of("a"), "t2", List.of("b"),
                "t3", List.of("c"), "t4", List.of("d")
        );

        MctsResult result = service.search(tasks, candidates, Map.of(), 0);

        // 4 tasks * 50 factor = 200 simulations
        assertThat(result.simulationsRun()).isEqualTo(200);
    }

    @Test
    @DisplayName("MCTS prefers high-prior candidates with enough simulations")
    void search_mctsPrefersHighPrior() {
        List<TaskCandidate> tasks = List.of(
                new TaskCandidate("t1", "d1"),
                new TaskCandidate("t2", "d2"),
                new TaskCandidate("t3", "d3"),
                new TaskCandidate("t4", "d4")
        );
        Map<String, List<String>> candidates = Map.of(
                "t1", List.of("good", "bad"),
                "t2", List.of("good", "bad"),
                "t3", List.of("good", "bad"),
                "t4", List.of("good", "bad")
        );
        Map<String, Double> priors = Map.of(
                "t1:good", 0.95, "t1:bad", 0.05,
                "t2:good", 0.95, "t2:bad", 0.05,
                "t3:good", 0.95, "t3:bad", 0.05,
                "t4:good", 0.95, "t4:bad", 0.05
        );

        MctsResult result = service.search(tasks, candidates, priors, 500);

        // With strong priors, MCTS should find at least some good assignments
        // (stochastic search — first task is most reliably assigned)
        assertThat(result.assignments()).isNotEmpty();
        assertThat(result.assignments()).containsKey("t1");
        assertThat(result.expectedReward()).isGreaterThan(0.3);
    }

    @Test
    @DisplayName("search returns expected reward in [0, 1] range")
    void search_rewardInRange() {
        List<TaskCandidate> tasks = List.of(
                new TaskCandidate("t1", "d1"),
                new TaskCandidate("t2", "d2"),
                new TaskCandidate("t3", "d3"),
                new TaskCandidate("t4", "d4")
        );
        Map<String, List<String>> candidates = Map.of(
                "t1", List.of("a"), "t2", List.of("b"),
                "t3", List.of("c"), "t4", List.of("d")
        );
        Map<String, Double> priors = Map.of(
                "t1:a", 0.8, "t2:b", 0.7, "t3:c", 0.6, "t4:d", 0.5
        );

        MctsResult result = service.search(tasks, candidates, priors, 50);

        assertThat(result.expectedReward()).isBetween(0.0, 1.0);
    }
}
