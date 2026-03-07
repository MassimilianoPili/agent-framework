package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PheromoneService} — ACO pheromone workflow learning.
 *
 * @see <a href="https://mitpress.mit.edu/9780262042192/">
 *     Dorigo &amp; Stützle (2004), Ant Colony Optimization</a>
 */
@ExtendWith(MockitoExtension.class)
class PheromoneServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private PheromoneService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(valueOps.get(anyString())).thenReturn(null);

        service = new PheromoneService(redisTemplate, new ObjectMapper());
        // Set @Value fields that Spring would normally inject
        ReflectionTestUtils.setField(service, "evaporationRate", 0.1);
        ReflectionTestUtils.setField(service, "initialPheromone", 1.0);
        ReflectionTestUtils.setField(service, "alpha", 1.0);
        // Trigger @PostConstruct manually (creates fresh matrix since Redis returns null)
        service.init();
    }

    @Test
    void depositForPlan_calculatesCorrectDelta() {
        // Plan: CM→BE, CM→FE (2 edges, reward=1.0 → delta=0.5)
        Plan plan = new Plan(UUID.randomUUID(), "test");
        plan.addItem(item("CM-001", WorkerType.CONTEXT_MANAGER, List.of()));
        plan.addItem(item("BE-001", WorkerType.BE, List.of("CM-001")));
        plan.addItem(item("FE-001", WorkerType.FE, List.of("CM-001")));

        service.depositForPlan(plan, 1.0);

        PheromoneMatrix m = service.getMatrix();
        // Each edge should receive delta = 1.0 / 2 = 0.5
        // Initial pheromone is 1.0, so total = 1.0 + 0.5 = 1.5
        assertThat(m.get(WorkerType.CONTEXT_MANAGER, WorkerType.BE))
                .isCloseTo(1.5, within(1e-9));
        assertThat(m.get(WorkerType.CONTEXT_MANAGER, WorkerType.FE))
                .isCloseTo(1.5, within(1e-9));
        // Unrelated edges should still be 1.0
        assertThat(m.get(WorkerType.BE, WorkerType.FE)).isCloseTo(1.0, within(1e-9));

        // Verify Redis save was called
        verify(valueOps).set(eq("pheromone:matrix"), anyString());
    }

    @Test
    void evaporate_appliesRho() {
        service.evaporate();

        PheromoneMatrix m = service.getMatrix();
        // Default evaporationRate=0.1 (from @Value default), initial=1.0
        // After evaporation: 1.0 * (1 - 0.1) = 0.9
        assertThat(m.get(WorkerType.BE, WorkerType.FE))
                .isCloseTo(0.9, within(1e-9));

        // Verify Redis save after evaporation
        verify(valueOps).set(eq("pheromone:matrix"), anyString());
    }

    @Test
    void formatHintsForPlanner_producesReadableOutput() {
        // Deposit pheromone to create a visible pattern
        Plan plan = new Plan(UUID.randomUUID(), "hints-test");
        plan.addItem(item("CM-001", WorkerType.CONTEXT_MANAGER, List.of()));
        plan.addItem(item("BE-001", WorkerType.BE, List.of("CM-001")));
        service.depositForPlan(plan, 5.0);

        String hints = service.formatHintsForPlanner();

        // Should contain readable workflow suggestions
        assertThat(hints).isNotEmpty();
        assertThat(hints).contains("CONTEXT_MANAGER");
        assertThat(hints).contains("BE");
        assertThat(hints).contains("recommended");
    }

    @Test
    void depositForPlan_zeroReward_noOp() {
        Plan plan = new Plan(UUID.randomUUID(), "zero-reward");
        plan.addItem(item("CM-001", WorkerType.CONTEXT_MANAGER, List.of()));
        plan.addItem(item("BE-001", WorkerType.BE, List.of("CM-001")));

        service.depositForPlan(plan, 0.0);

        // Matrix should be unchanged (all 1.0)
        assertThat(service.getMatrix().get(WorkerType.CONTEXT_MANAGER, WorkerType.BE))
                .isCloseTo(1.0, within(1e-9));
        // Redis save should NOT be called for zero reward
        verify(valueOps, never()).set(anyString(), anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private PlanItem item(String taskKey, WorkerType workerType, List<String> deps) {
        return new PlanItem(UUID.randomUUID(), 0, taskKey, "Title " + taskKey,
                "Desc", workerType, null, deps);
    }
}
