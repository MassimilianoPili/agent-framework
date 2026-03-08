package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.ByzantineFaultToleranceService.BFTConsensusReport;
import com.agentframework.orchestrator.domain.DispatchAttempt;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.DispatchAttemptRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ByzantineFaultToleranceService}.
 *
 * <p>Verifies majority voting consensus, Byzantine worker detection,
 * and empty-attempt edge cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class ByzantineFaultToleranceServiceTest {

    @Mock private DispatchAttemptRepository dispatchAttemptRepository;
    @Mock private PlanItemRepository        planItemRepository;
    @Mock private TaskOutcomeRepository     taskOutcomeRepository;

    private ByzantineFaultToleranceService service;

    @BeforeEach
    void setUp() {
        service = new ByzantineFaultToleranceService(
                dispatchAttemptRepository, planItemRepository, taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "majorityThreshold", 0.67);
    }

    private DispatchAttempt makeAttempt(UUID itemId, int attemptNumber, boolean success) {
        PlanItem item = new PlanItem(itemId, 1, "T1", "title", "desc",
                                     WorkerType.BE, "be-java", List.of(), List.of());
        DispatchAttempt attempt = new DispatchAttempt(UUID.randomUUID(), item, attemptNumber);
        attempt.complete(success, success ? null : "error", 100L);
        return attempt;
    }

    @Test
    @DisplayName("analyseItem with majority success reaches consensus")
    void analyseItem_majoritySuccess_consensusReached() {
        UUID itemId = UUID.randomUUID();

        // 3 success + 1 failure = 75% > threshold 0.67 → clear majority
        List<DispatchAttempt> attempts = List.of(
                makeAttempt(itemId, 1, true),
                makeAttempt(itemId, 2, true),
                makeAttempt(itemId, 3, true),
                makeAttempt(itemId, 4, false)  // Byzantine: fails while others succeed
        );

        when(dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId))
                .thenReturn(attempts);

        BFTConsensusReport report = service.analyseItem(itemId);

        assertThat(report.consensusOutcome()).isEqualTo("success");
        assertThat(report.consensusReached()).isTrue();
        assertThat(report.majorityVotes()).isEqualTo(3);
        assertThat(report.totalVoters()).isEqualTo(4);
        assertThat(report.byzantineWorkers()).hasSize(1);
        assertThat(report.byzantineWorkers().get(0)).isEqualTo("attempt-4");
    }

    @Test
    @DisplayName("analyseItem with no consensus (50/50 split)")
    void analyseItem_noConsensus_splits() {
        UUID itemId = UUID.randomUUID();

        List<DispatchAttempt> attempts = List.of(
                makeAttempt(itemId, 1, true),
                makeAttempt(itemId, 2, false)  // 50% each — no majority above 0.67
        );

        when(dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId))
                .thenReturn(attempts);

        BFTConsensusReport report = service.analyseItem(itemId);

        // 1/2 = 0.5 which is NOT > majorityThreshold=0.67
        assertThat(report.consensusReached()).isFalse();
        assertThat(report.totalVoters()).isEqualTo(2);
    }

    @Test
    @DisplayName("analyseItem with no attempts returns unknown")
    void analyseItem_noAttempts_returnsUnknown() {
        UUID itemId = UUID.randomUUID();
        when(dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId))
                .thenReturn(List.of());

        BFTConsensusReport report = service.analyseItem(itemId);

        assertThat(report.consensusOutcome()).isEqualTo("unknown");
        assertThat(report.consensusReached()).isFalse();
        assertThat(report.totalVoters()).isEqualTo(0);
    }

    @Test
    @DisplayName("analyseAllRetries skips items with single attempt")
    void analyseAllRetries_skipsLowAttemptItems() {
        UUID planId = UUID.randomUUID();
        UUID itemId1 = UUID.randomUUID();
        UUID itemId2 = UUID.randomUUID();

        PlanItem item1 = new PlanItem(itemId1, 1, "T1", "title", "desc",
                                      WorkerType.BE, "be-java", List.of(), List.of());
        PlanItem item2 = new PlanItem(itemId2, 2, "T2", "title", "desc",
                                      WorkerType.BE, "be-java", List.of(), List.of());

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item1, item2));
        // item1: 2 attempts → included
        when(dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId1))
                .thenReturn(List.of(makeAttempt(itemId1, 1, true), makeAttempt(itemId1, 2, true)));
        // item2: 1 attempt → skipped
        when(dispatchAttemptRepository.findByItemIdOrderByAttemptNumberAsc(itemId2))
                .thenReturn(List.of(makeAttempt(itemId2, 1, true)));

        var results = service.analyseAllRetries(planId);

        assertThat(results).hasSize(1);
        assertThat(results).containsKey(itemId1);
        assertThat(results).doesNotContainKey(itemId2);
    }
}
