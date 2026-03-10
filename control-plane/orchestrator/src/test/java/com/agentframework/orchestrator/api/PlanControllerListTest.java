package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.api.dto.PlanResponse;
import com.agentframework.orchestrator.budget.CovarianceMatrix;
import com.agentframework.orchestrator.analytics.QueueAnalyzer;
import com.agentframework.orchestrator.analytics.ShapleyDagService;
import com.agentframework.orchestrator.budget.TokenLedgerService;
import com.agentframework.orchestrator.budget.PortfolioOptimizer;
import com.agentframework.orchestrator.analytics.RootCauseAnalyzer;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.eventsourcing.HashChainVerifier;
import com.agentframework.orchestrator.domain.PlanStatus;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.graph.CriticalPathCalculator;
import com.agentframework.orchestrator.graph.PlanGraphService;
import com.agentframework.orchestrator.graph.SpectralAnalyzer;
import com.agentframework.orchestrator.orchestration.OrchestrationService;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.agentframework.orchestrator.repository.FileModificationRepository;
import com.agentframework.orchestrator.repository.PlanRepository;
import com.agentframework.orchestrator.repository.QualityGateReportRepository;
import com.agentframework.orchestrator.service.PlanSnapshotService;
import com.agentframework.orchestrator.sse.SseEmitterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlanController#listPlans(int, String)} — covering
 * empty result and status-based filtering.
 */
@ExtendWith(MockitoExtension.class)
class PlanControllerListTest {

    @Mock private OrchestrationService orchestrationService;
    @Mock private PlanSnapshotService snapshotService;
    @Mock private QualityGateReportRepository reportRepository;
    @Mock private PlanGraphService planGraphService;
    @Mock private CriticalPathCalculator criticalPathCalculator;
    @Mock private SpectralAnalyzer spectralAnalyzer;
    @Mock private SseEmitterRegistry sseEmitterRegistry;
    @Mock private PlanItemRepository planItemRepository;
    @Mock private PlanRepository planRepository;
    @Mock private FileModificationRepository fileModificationRepository;
    @Mock private TokenLedgerService tokenLedgerService;
    @Mock private ShapleyDagService shapleyDagService;
    @Mock private HashChainVerifier hashChainVerifier;
    @Mock private QueueAnalyzer queueAnalyzer;
    @Mock private com.agentframework.orchestrator.graph.DagHashService dagHashService;
    @Mock private com.agentframework.orchestrator.council.CouncilCommitmentRepository councilCommitmentRepository;

    private PlanController controller;

    @BeforeEach
    void setUp() {
        controller = new PlanController(
                orchestrationService, snapshotService, reportRepository,
                planGraphService, criticalPathCalculator, spectralAnalyzer,
                sseEmitterRegistry, planItemRepository, planRepository,
                Optional.empty(), Optional.empty(), new ObjectMapper(),
                fileModificationRepository,
                tokenLedgerService,
                shapleyDagService,
                hashChainVerifier,
                queueAnalyzer,
                Optional.empty(),
                dagHashService,
                councilCommitmentRepository);
    }

    @Test
    void listPlans_noPlans_returns200WithEmptyList() {
        when(planRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        ResponseEntity<List<PlanResponse>> response = controller.listPlans(20, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    @Test
    void listPlans_withStatus_filtersCorrectly() {
        Plan running1 = new Plan(UUID.randomUUID(), "spec 1");
        running1.transitionTo(PlanStatus.RUNNING);

        Plan pending = new Plan(UUID.randomUUID(), "spec 2");
        // PENDING is the initial state — not returned by the RUNNING filter

        when(planRepository.findByStatusOrderByCreatedAtDesc(PlanStatus.RUNNING))
                .thenReturn(List.of(running1));

        ResponseEntity<List<PlanResponse>> response = controller.listPlans(20, "RUNNING");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).status()).isEqualTo("RUNNING");
    }
}
