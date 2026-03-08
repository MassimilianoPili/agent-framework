package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ReflectiveDispatchService.FDTDispatchReport;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReflectiveDispatchService}.
 *
 * <p>Verifies FDT policy recommendation with sufficient data, insufficient data,
 * and filtering by worker type and similarity threshold.</p>
 */
@ExtendWith(MockitoExtension.class)
class ReflectiveDispatchServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private ReflectiveDispatchService service;

    @BeforeEach
    void setUp() {
        service = new ReflectiveDispatchService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "similarityThreshold", 0.85);
        ReflectionTestUtils.setField(service, "minSimilarTasks", 3);
    }

    /**
     * Creates a mock row matching findSimilarOutcomes format.
     * [0]=id(UUID), [1]=plan_id(UUID), [2]=task_key, [3]=worker_type,
     * [4]=worker_profile, [5]=gp_mu, [6]=actual_reward, [7]=similarity
     */
    private Object[] makeSimilar(String taskKey, String workerType, String profile,
                                  double reward, double similarity) {
        return new Object[]{UUID.randomUUID(), UUID.randomUUID(), taskKey,
                            workerType, profile, 0.8, reward, similarity};
    }

    @Test
    @DisplayName("computeReflectivePolicy with sufficient similar tasks returns best profile")
    void computeReflectivePolicy_sufficientData_returnsBestProfile() {
        String embedding = "[0.1,0.2,0.3]";

        List<Object[]> candidates = new ArrayList<>();
        // be-java: rewards 0.9, 0.85, 0.88 → mean 0.877
        candidates.add(makeSimilar("task-1", "BE", "be-java", 0.9,  0.92));
        candidates.add(makeSimilar("task-2", "BE", "be-java", 0.85, 0.91));
        candidates.add(makeSimilar("task-3", "BE", "be-java", 0.88, 0.90));
        // be-go: rewards 0.7, 0.72 → mean 0.71
        candidates.add(makeSimilar("task-4", "BE", "be-go",   0.7,  0.87));
        candidates.add(makeSimilar("task-5", "BE", "be-go",   0.72, 0.86));

        when(taskOutcomeRepository.findSimilarOutcomes(embedding, 100)).thenReturn(candidates);

        FDTDispatchReport report = service.computeReflectivePolicy(embedding, "BE");

        assertThat(report).isNotNull();
        assertThat(report.recommendedProfile()).isEqualTo("be-java");
        assertThat(report.policyReward()).isCloseTo(0.877, within(0.01));
        assertThat(report.similarCount()).isEqualTo(5);
        assertThat(report.similarTaskKeys()).containsExactlyInAnyOrder(
                "task-1", "task-2", "task-3", "task-4", "task-5");
    }

    @Test
    @DisplayName("computeReflectivePolicy with insufficient similar tasks returns null")
    void computeReflectivePolicy_insufficientData_returnsNull() {
        String embedding = "[0.1,0.2,0.3]";

        List<Object[]> candidates = new ArrayList<>();
        // Only 2 tasks (below minSimilarTasks=3)
        candidates.add(makeSimilar("task-1", "BE", "be-java", 0.9, 0.90));
        candidates.add(makeSimilar("task-2", "BE", "be-java", 0.8, 0.88));

        when(taskOutcomeRepository.findSimilarOutcomes(embedding, 100)).thenReturn(candidates);

        FDTDispatchReport report = service.computeReflectivePolicy(embedding, "BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("computeReflectivePolicy filters out wrong worker type and low similarity")
    void computeReflectivePolicy_filtersWrongTypeAndLowSimilarity() {
        String embedding = "[0.1,0.2,0.3]";

        List<Object[]> candidates = new ArrayList<>();
        // Correct type + high similarity
        candidates.add(makeSimilar("task-1", "BE", "be-java", 0.9, 0.92));
        candidates.add(makeSimilar("task-2", "BE", "be-java", 0.8, 0.90));
        candidates.add(makeSimilar("task-3", "BE", "be-java", 0.7, 0.88));
        // Wrong type (should be filtered)
        candidates.add(makeSimilar("task-4", "FE", "fe-react", 0.95, 0.95));
        // Below similarity threshold (0.84 < 0.85, should be filtered)
        candidates.add(makeSimilar("task-5", "BE", "be-go", 0.95, 0.84));

        when(taskOutcomeRepository.findSimilarOutcomes(embedding, 100)).thenReturn(candidates);

        FDTDispatchReport report = service.computeReflectivePolicy(embedding, "BE");

        assertThat(report).isNotNull();
        // Only 3 valid tasks after filtering
        assertThat(report.similarCount()).isEqualTo(3);
        assertThat(report.recommendedProfile()).isEqualTo("be-java");
    }
}
