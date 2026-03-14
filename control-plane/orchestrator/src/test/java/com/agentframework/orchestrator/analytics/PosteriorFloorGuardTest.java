package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.PosteriorFloorGuard.GuardedPosterior;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PosteriorFloorGuard}.
 *
 * <p>Verifies adaptive floor computation, clamping behaviour for both
 * floor and ceiling, and edge cases (zero observations, high observations).</p>
 */
@ExtendWith(MockitoExtension.class)
class PosteriorFloorGuardTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private PosteriorFloorGuard guard;

    @BeforeEach
    void setUp() {
        guard = new PosteriorFloorGuard(taskOutcomeRepository);
        ReflectionTestUtils.setField(guard, "epsilon", 0.01);
        ReflectionTestUtils.setField(guard, "smoothingK", 10);
    }

    @Test
    @DisplayName("computeFloor with zero observations returns 1/(0+k) = 0.1")
    void computeFloor_zeroObservations() {
        double floor = guard.computeFloor(0);
        assertThat(floor).isEqualTo(0.1); // 1/(0+10)
    }

    @Test
    @DisplayName("computeFloor with 90 observations returns 1/(90+10) = 0.01 = epsilon")
    void computeFloor_90observations_equalsEpsilon() {
        double floor = guard.computeFloor(90);
        assertThat(floor).isEqualTo(0.01); // max(0.01, 1/100) = 0.01
    }

    @Test
    @DisplayName("computeFloor with many observations returns epsilon (hard floor)")
    void computeFloor_manyObservations_returnsEpsilon() {
        double floor = guard.computeFloor(1000);
        assertThat(floor).isEqualTo(0.01); // 1/1010 < 0.01, so epsilon wins
    }

    @Test
    @DisplayName("computeFloor with 40 observations returns 1/50 = 0.02")
    void computeFloor_40observations() {
        double floor = guard.computeFloor(40);
        assertThat(floor).isCloseTo(0.02, within(1e-10)); // 1/(40+10) = 0.02
    }

    @Test
    @DisplayName("guard does not clamp when posterior is within bounds")
    void guard_withinBounds_noClamping() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", Integer.MAX_VALUE))
                .thenReturn(nRows(50));

        GuardedPosterior result = guard.guard("BE", "be-java", 0.5);

        assertThat(result.wasClamped()).isFalse();
        assertThat(result.original()).isEqualTo(0.5);
        assertThat(result.guarded()).isEqualTo(0.5);
        assertThat(result.observations()).isEqualTo(50);
    }

    @Test
    @DisplayName("guard clamps posterior below floor")
    void guard_belowFloor_clampsUp() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", Integer.MAX_VALUE))
                .thenReturn(nRows(0)); // floor = 1/(0+10) = 0.1

        GuardedPosterior result = guard.guard("BE", "be-java", 0.02);

        assertThat(result.wasClamped()).isTrue();
        assertThat(result.original()).isEqualTo(0.02);
        assertThat(result.guarded()).isEqualTo(0.1);
        assertThat(result.floor()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("guard clamps posterior above ceiling")
    void guard_aboveCeiling_clampsDown() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", Integer.MAX_VALUE))
                .thenReturn(nRows(0)); // ceiling = 1 - 0.1 = 0.9

        GuardedPosterior result = guard.guard("BE", "be-java", 0.98);

        assertThat(result.wasClamped()).isTrue();
        assertThat(result.original()).isEqualTo(0.98);
        assertThat(result.guarded()).isEqualTo(0.9);
        assertThat(result.ceiling()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("guard with many observations has tight bounds")
    void guard_manyObservations_tightBounds() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("FE", Integer.MAX_VALUE))
                .thenReturn(nRows(1000)); // floor = epsilon = 0.01

        GuardedPosterior result = guard.guard("FE", "fe-react", 0.005);

        assertThat(result.wasClamped()).isTrue();
        assertThat(result.guarded()).isEqualTo(0.01);
        assertThat(result.floor()).isEqualTo(0.01);
        assertThat(result.ceiling()).isEqualTo(0.99);
    }

    @Test
    @DisplayName("guard at exact floor is not clamped")
    void guard_atExactFloor_notClamped() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", Integer.MAX_VALUE))
                .thenReturn(nRows(0)); // floor = 0.1

        GuardedPosterior result = guard.guard("BE", "be-java", 0.1);

        assertThat(result.wasClamped()).isFalse();
        assertThat(result.guarded()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("guard at exact ceiling is not clamped")
    void guard_atExactCeiling_notClamped() {
        when(taskOutcomeRepository.findRewardTimeseriesByWorkerType("BE", Integer.MAX_VALUE))
                .thenReturn(nRows(0)); // ceiling = 0.9

        GuardedPosterior result = guard.guard("BE", "be-java", 0.9);

        assertThat(result.wasClamped()).isFalse();
        assertThat(result.guarded()).isEqualTo(0.9);
    }

    /**
     * Helper: creates N mock rows for findRewardTimeseriesByWorkerType.
     * Each row is [workerType(String), actual_reward(Number)].
     */
    private List<Object[]> nRows(int count) {
        return Collections.nCopies(count, new Object[]{"BE", 0.7});
    }
}
