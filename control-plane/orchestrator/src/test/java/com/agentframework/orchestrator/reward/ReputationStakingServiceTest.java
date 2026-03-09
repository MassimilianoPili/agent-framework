package com.agentframework.orchestrator.reward;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReputationStakingService} (#47).
 *
 * <p>Verifies the staking formula, complexity clamping, settlement mechanics,
 * and cold-start behavior.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReputationStakingService (#47)")
class ReputationStakingServiceTest {

    @Mock private WorkerEloStatsRepository eloStatsRepository;

    private ReputationStakingService service;

    @BeforeEach
    void setUp() {
        service = new ReputationStakingService(eloStatsRepository);
        ReflectionTestUtils.setField(service, "baseStakeRate", 0.05);
        ReflectionTestUtils.setField(service, "maxComplexity", 2.0);
        ReflectionTestUtils.setField(service, "successBonusRate", 0.30);
    }

    @Test
    @DisplayName("stake computes correct amount: baseRate × ELO × (1 + σ²)")
    void stake_computesCorrectAmount() {
        WorkerEloStats stats = new WorkerEloStats("be-java");
        ReflectionTestUtils.setField(stats, "eloRating", 1600.0);

        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.of(stats));

        double staked = service.stake("be-java", 0.5);

        // 0.05 × 1600 × (1 + 0.5) = 0.05 × 1600 × 1.5 = 120.0
        assertThat(staked).isCloseTo(120.0, within(0.01));
        assertThat(stats.getStakedReputation()).isCloseTo(120.0, within(0.01));
        assertThat(stats.getTotalStaked()).isCloseTo(120.0, within(0.01));
        verify(eloStatsRepository).save(stats);
    }

    @Test
    @DisplayName("stake clamps σ² to maxComplexity")
    void stake_clampsComplexity() {
        WorkerEloStats stats = new WorkerEloStats("be-go");
        ReflectionTestUtils.setField(stats, "eloRating", 2000.0);

        when(eloStatsRepository.findById("be-go")).thenReturn(Optional.of(stats));

        double staked = service.stake("be-go", 5.0); // σ²=5.0 > maxComplexity=2.0

        // 0.05 × 2000 × (1 + 2.0) = 0.05 × 2000 × 3.0 = 300.0
        assertThat(staked).isCloseTo(300.0, within(0.01));
    }

    @Test
    @DisplayName("stake with σ² = 0 uses basale amount only")
    void stake_zeroSigma_basaleOnly() {
        WorkerEloStats stats = new WorkerEloStats("fe-react");
        ReflectionTestUtils.setField(stats, "eloRating", 1600.0);

        when(eloStatsRepository.findById("fe-react")).thenReturn(Optional.of(stats));

        double staked = service.stake("fe-react", 0.0);

        // 0.05 × 1600 × (1 + 0) = 80.0
        assertThat(staked).isCloseTo(80.0, within(0.01));
    }

    @Test
    @DisplayName("settle success: ELO increases by stake × bonusRate")
    void settle_success_addsBonus() {
        WorkerEloStats stats = new WorkerEloStats("be-java");
        ReflectionTestUtils.setField(stats, "eloRating", 1600.0);
        ReflectionTestUtils.setField(stats, "stakedReputation", 120.0);

        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.of(stats));

        service.settle("be-java", 120.0, true);

        // ELO: 1600 + 120 × 0.30 = 1636.0
        assertThat(stats.getEloRating()).isCloseTo(1636.0, within(0.01));
        assertThat(stats.getStakedReputation()).isCloseTo(0.0, within(0.01));
        assertThat(stats.getTotalForfeited()).isCloseTo(0.0, within(0.01));
        verify(eloStatsRepository).save(stats);
    }

    @Test
    @DisplayName("settle failure: ELO decreases by stake amount, totalForfeited increases")
    void settle_failure_deductsStake() {
        WorkerEloStats stats = new WorkerEloStats("be-go");
        ReflectionTestUtils.setField(stats, "eloRating", 1800.0);
        ReflectionTestUtils.setField(stats, "stakedReputation", 90.0);

        when(eloStatsRepository.findById("be-go")).thenReturn(Optional.of(stats));

        service.settle("be-go", 90.0, false);

        // ELO: 1800 - 90 = 1710.0
        assertThat(stats.getEloRating()).isCloseTo(1710.0, within(0.01));
        assertThat(stats.getStakedReputation()).isCloseTo(0.0, within(0.01));
        assertThat(stats.getTotalForfeited()).isCloseTo(90.0, within(0.01));
    }

    @Test
    @DisplayName("stake for new profile creates stats with default ELO 1600")
    void stake_coldStart_createsStats() {
        when(eloStatsRepository.findById("be-rust")).thenReturn(Optional.empty());

        double staked = service.stake("be-rust", 0.0);

        // New profile: ELO=1600, basale = 0.05 × 1600 × 1.0 = 80.0
        assertThat(staked).isCloseTo(80.0, within(0.01));
        verify(eloStatsRepository).save(any(WorkerEloStats.class));
    }

    @Test
    @DisplayName("settle with zero stake is a no-op")
    void settle_zeroStake_noOp() {
        service.settle("be-java", 0.0, true);

        verify(eloStatsRepository, never()).findById(any());
        verify(eloStatsRepository, never()).save(any());
    }

    @Test
    @DisplayName("settle with unknown profile logs warning")
    void settle_unknownProfile_logsWarning() {
        when(eloStatsRepository.findById("unknown")).thenReturn(Optional.empty());

        // Should not throw
        service.settle("unknown", 100.0, false);

        verify(eloStatsRepository, never()).save(any());
    }
}
