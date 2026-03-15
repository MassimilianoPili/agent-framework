package com.agentframework.orchestrator.workspace;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link WorkspaceCleanupScheduler} — stale workspace cleanup logic.
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceCleanupSchedulerTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private WorkspaceManager workspaceManager;

    @InjectMocks
    private WorkspaceCleanupScheduler scheduler;

    @Captor
    private ArgumentCaptor<Instant> cutoffCaptor;

    @Test
    void cleanupStaleWorkspaces_noStalePlans_doesNothing() {
        when(planRepository.findPlansWithStaleWorkspaces(any())).thenReturn(List.of());

        scheduler.cleanupStaleWorkspaces();

        verifyNoInteractions(workspaceManager);
        verify(planRepository, never()).save(any());
    }

    @Test
    void cleanupStaleWorkspaces_destroysAndClearsVolume() {
        Plan plan = makePlan("ws-abc123");
        when(planRepository.findPlansWithStaleWorkspaces(any())).thenReturn(List.of(plan));

        scheduler.cleanupStaleWorkspaces();

        verify(workspaceManager).destroyWorkspace("ws-abc123");
        assertThat(plan.getWorkspaceVolume()).isNull();
        verify(planRepository).save(plan);
    }

    @Test
    void cleanupStaleWorkspaces_multiplePlans_allCleaned() {
        Plan p1 = makePlan("ws-001");
        Plan p2 = makePlan("ws-002");
        Plan p3 = makePlan("ws-003");
        when(planRepository.findPlansWithStaleWorkspaces(any())).thenReturn(List.of(p1, p2, p3));

        scheduler.cleanupStaleWorkspaces();

        verify(workspaceManager).destroyWorkspace("ws-001");
        verify(workspaceManager).destroyWorkspace("ws-002");
        verify(workspaceManager).destroyWorkspace("ws-003");
        verify(planRepository, times(3)).save(any());
    }

    @Test
    void cleanupStaleWorkspaces_destroyFailure_continuesOthers() {
        Plan p1 = makePlan("ws-fail");
        Plan p2 = makePlan("ws-ok");
        when(planRepository.findPlansWithStaleWorkspaces(any())).thenReturn(List.of(p1, p2));
        doThrow(new RuntimeException("disk error")).when(workspaceManager).destroyWorkspace("ws-fail");

        scheduler.cleanupStaleWorkspaces();

        // p1 failed but p2 should still be cleaned
        verify(workspaceManager).destroyWorkspace("ws-ok");
        assertThat(p2.getWorkspaceVolume()).isNull();
        verify(planRepository).save(p2);
    }

    @Test
    void cleanupStaleWorkspaces_verifiesCutoffInstant() {
        when(planRepository.findPlansWithStaleWorkspaces(cutoffCaptor.capture())).thenReturn(List.of());

        Instant before = Instant.now().minus(Duration.ofHours(1));
        scheduler.cleanupStaleWorkspaces();
        Instant after = Instant.now().minus(Duration.ofHours(1));

        Instant captured = cutoffCaptor.getValue();
        assertThat(captured).isBetween(before, after);
    }

    private Plan makePlan(String workspaceVolume) {
        Plan plan = new Plan(UUID.randomUUID(), "test spec");
        plan.setWorkspaceVolume(workspaceVolume);
        return plan;
    }
}
