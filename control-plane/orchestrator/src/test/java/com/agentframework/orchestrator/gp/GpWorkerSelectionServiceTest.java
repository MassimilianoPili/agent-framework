package com.agentframework.orchestrator.gp;

import com.agentframework.gp.model.GpPrediction;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GpWorkerSelectionService}.
 * Verifies profile selection logic: single profile shortcut, multi-profile GP selection,
 * cold-start behavior, and tie-break to default profile.
 */
@ExtendWith(MockitoExtension.class)
class GpWorkerSelectionServiceTest {

    @Mock private TaskOutcomeService outcomeService;
    @Mock private WorkerProfileRegistry profileRegistry;

    private GpWorkerSelectionService service;

    @BeforeEach
    void setUp() {
        service = new GpWorkerSelectionService(outcomeService, profileRegistry, Optional.empty());
    }

    // ── Single profile (skip GP) ────────────────────────────────────────────

    @Test
    @DisplayName("single profile returns it directly without GP prediction")
    void singleProfile_skipsGp() {
        when(profileRegistry.profilesForWorkerType(WorkerType.FE)).thenReturn(List.of("fe-react"));
        when(profileRegistry.resolveDefaultProfile(WorkerType.FE)).thenReturn("fe-react");

        var result = service.selectProfile(WorkerType.FE, "Build UI", "React components");

        assertThat(result.selectedProfile()).isEqualTo("fe-react");
        assertThat(result.selectedPrediction()).isNull();
        assertThat(result.allPredictions()).isEmpty();
        verifyNoInteractions(outcomeService);
    }

    @Test
    @DisplayName("zero profiles returns workerType name as fallback")
    void zeroProfiles_fallsBackToWorkerTypeName() {
        when(profileRegistry.profilesForWorkerType(WorkerType.AI_TASK)).thenReturn(List.of());
        when(profileRegistry.resolveDefaultProfile(WorkerType.AI_TASK)).thenReturn(null);

        var result = service.selectProfile(WorkerType.AI_TASK, "Process data", "Run analysis");

        assertThat(result.selectedProfile()).isEqualTo("ai_task");
        assertThat(result.selectedPrediction()).isNull();
        verifyNoInteractions(outcomeService);
    }

    // ── Multi-profile GP selection ──────────────────────────────────────────

    @Test
    @DisplayName("multi-profile selects highest mu profile")
    void multiProfile_selectsHighestMu() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go", "be-rust"));
        when(profileRegistry.resolveDefaultProfile(WorkerType.BE)).thenReturn("be-java");

        float[] embedding = {0.1f, 0.2f};
        when(outcomeService.embedTask("Build API", "REST endpoints")).thenReturn(embedding);
        when(outcomeService.predict(embedding, "BE", "be-java"))
                .thenReturn(new GpPrediction(0.65, 0.10));
        when(outcomeService.predict(embedding, "BE", "be-go"))
                .thenReturn(new GpPrediction(0.80, 0.08));  // highest mu
        when(outcomeService.predict(embedding, "BE", "be-rust"))
                .thenReturn(new GpPrediction(0.70, 0.12));

        var result = service.selectProfile(WorkerType.BE, "Build API", "REST endpoints");

        assertThat(result.selectedProfile()).isEqualTo("be-go");
        assertThat(result.selectedPrediction().mu()).isEqualTo(0.80);
        assertThat(result.allPredictions()).hasSize(3);
        verify(outcomeService).recordOutcomeAtDispatch(
                isNull(), isNull(), eq(""), eq("BE"), eq("be-go"),
                eq(embedding), eq(result.selectedPrediction()));
    }

    @Test
    @DisplayName("tie-break prefers default profile when mu values are equal")
    void tieBreak_prefersDefault() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));
        when(profileRegistry.resolveDefaultProfile(WorkerType.BE)).thenReturn("be-java");

        float[] embedding = {0.5f};
        when(outcomeService.embedTask(any(), any())).thenReturn(embedding);
        // Both return same mu — default (be-java) should win
        when(outcomeService.predict(embedding, "BE", "be-java"))
                .thenReturn(new GpPrediction(0.70, 0.10));
        when(outcomeService.predict(embedding, "BE", "be-go"))
                .thenReturn(new GpPrediction(0.70, 0.05));

        var result = service.selectProfile(WorkerType.BE, "Task", "Desc");

        assertThat(result.selectedProfile()).isEqualTo("be-java");
    }

    @Test
    @DisplayName("cold-start with identical prior predictions selects default profile")
    void coldStart_selectsDefault() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go", "be-rust", "be-node"));
        when(profileRegistry.resolveDefaultProfile(WorkerType.BE)).thenReturn("be-java");

        float[] embedding = {0.3f, 0.4f};
        when(outcomeService.embedTask(any(), any())).thenReturn(embedding);
        // All profiles return the same prior (cold-start scenario)
        GpPrediction prior = new GpPrediction(0.5, 1.0);
        when(outcomeService.predict(eq(embedding), eq("BE"), anyString())).thenReturn(prior);

        var result = service.selectProfile(WorkerType.BE, "New task", "No history");

        // Cold-start: all mu identical → tie-break → default wins
        assertThat(result.selectedProfile()).isEqualTo("be-java");
        assertThat(result.allPredictions()).hasSize(4);
        // All predictions should be the same prior
        result.allPredictions().values().forEach(p -> {
            assertThat(p.mu()).isEqualTo(0.5);
            assertThat(p.sigma2()).isEqualTo(1.0);
        });
    }

    @Test
    @DisplayName("records outcome at dispatch after selection")
    void recordsOutcomeAtDispatch() {
        when(profileRegistry.profilesForWorkerType(WorkerType.BE))
                .thenReturn(List.of("be-java", "be-go"));
        when(profileRegistry.resolveDefaultProfile(WorkerType.BE)).thenReturn("be-java");

        float[] embedding = {0.1f};
        when(outcomeService.embedTask(any(), any())).thenReturn(embedding);
        when(outcomeService.predict(embedding, "BE", "be-java"))
                .thenReturn(new GpPrediction(0.60, 0.10));
        when(outcomeService.predict(embedding, "BE", "be-go"))
                .thenReturn(new GpPrediction(0.90, 0.05));

        service.selectProfile(WorkerType.BE, "API", "Desc");

        // Verify outcome recorded for the selected profile (be-go)
        verify(outcomeService).recordOutcomeAtDispatch(
                isNull(), isNull(), eq(""), eq("BE"), eq("be-go"),
                eq(embedding), argThat(p -> p.mu() == 0.90));
    }
}
