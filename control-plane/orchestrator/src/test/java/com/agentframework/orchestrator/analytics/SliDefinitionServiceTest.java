package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.SliDefinitionService.SliReport;
import com.agentframework.orchestrator.analytics.SliDefinitionService.SliValue;
import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SliDefinitionService}.
 */
@ExtendWith(MockitoExtension.class)
class SliDefinitionServiceTest {

    @Mock private PlanItemRepository planItemRepository;
    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private SliDefinitionService service;

    @BeforeEach
    void setUp() {
        service = new SliDefinitionService(planItemRepository, taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "qualityThreshold", 0.5);
    }

    @Test
    @DisplayName("buildReport with no items returns default values")
    void buildReport_noItems() {
        SliReport report = service.buildReport("BE", List.of(), List.of(), List.of());

        assertThat(report.workerType()).isEqualTo("BE");
        assertThat(report.totalItems()).isZero();
        SliValue avail = report.findByName("availability");
        assertThat(avail).isNotNull();
        assertThat(avail.value()).isEqualTo(1.0); // no failures = 100% available
    }

    @Test
    @DisplayName("availability is DONE / (DONE + FAILED)")
    void availability_computation() {
        List<PlanItem> done = makeItems(8); // 8 DONE
        List<PlanItem> failed = makeItems(2); // 2 FAILED

        SliReport report = service.buildReport("BE", done, failed, List.of());

        assertThat(report.findByName("availability").value()).isCloseTo(0.8, within(1e-10));
        assertThat(report.totalItems()).isEqualTo(10);
    }

    @Test
    @DisplayName("quality is fraction of rewards above threshold")
    void quality_computation() {
        List<Object[]> rewards = List.of(
                new Object[]{"BE", 0.8},  // above 0.5
                new Object[]{"BE", 0.3},  // below 0.5
                new Object[]{"BE", 0.6},  // above 0.5
                new Object[]{"BE", 0.1}   // below 0.5
        );

        SliReport report = service.buildReport("BE", List.of(), List.of(), rewards);

        assertThat(report.findByName("quality").value()).isCloseTo(0.5, within(1e-10));
    }

    @Test
    @DisplayName("latency percentiles are computed from dispatchedAt → completedAt")
    void latency_percentiles() {
        List<PlanItem> doneItems = new ArrayList<>();
        // Create items with known latencies: 100ms, 200ms, 300ms, ..., 1000ms
        for (int i = 1; i <= 10; i++) {
            PlanItem item = makeItem();
            item.setDispatchedAt(Instant.ofEpochMilli(0));
            item.setCompletedAt(Instant.ofEpochMilli(i * 100L));
            doneItems.add(item);
        }

        SliReport report = service.buildReport("BE", doneItems, List.of(), List.of());

        SliValue p50 = report.findByName("latency_p50_seconds");
        SliValue p95 = report.findByName("latency_p95_seconds");
        SliValue p99 = report.findByName("latency_p99_seconds");

        assertThat(p50).isNotNull();
        assertThat(p95).isNotNull();
        assertThat(p99).isNotNull();

        // P50 of [100..1000] = 500ms = 0.5s
        assertThat(p50.value()).isCloseTo(0.5, within(0.1));
        // P99 should be close to 1.0s
        assertThat(p99.value()).isGreaterThanOrEqualTo(0.9);
    }

    @Test
    @DisplayName("latency is not computed when timestamps are missing")
    void latency_noTimestamps() {
        List<PlanItem> doneItems = makeItems(5); // no dispatchedAt/completedAt

        SliReport report = service.buildReport("BE", doneItems, List.of(), List.of());

        assertThat(report.findByName("latency_p50_seconds")).isNull();
    }

    @Test
    @DisplayName("throughput counts completed tasks")
    void throughput_counts() {
        List<PlanItem> doneItems = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            PlanItem item = makeItem();
            item.setCompletedAt(Instant.now());
            doneItems.add(item);
        }

        SliReport report = service.buildReport("BE", doneItems, List.of(), List.of());

        assertThat(report.findByName("throughput").value()).isEqualTo(7.0);
    }

    @Test
    @DisplayName("findByName returns null for unknown SLI")
    void findByName_unknown() {
        SliReport report = service.buildReport("BE", List.of(), List.of(), List.of());
        assertThat(report.findByName("nonexistent")).isNull();
    }

    @Test
    @DisplayName("percentile handles single element list")
    void percentile_singleElement() {
        assertThat(SliDefinitionService.percentile(List.of(42L), 0.50)).isEqualTo(42.0);
        assertThat(SliDefinitionService.percentile(List.of(42L), 0.99)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("percentile handles empty list")
    void percentile_emptyList() {
        assertThat(SliDefinitionService.percentile(List.of(), 0.50)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("all rewards above threshold gives quality 1.0")
    void quality_allAbove() {
        List<Object[]> rewards = List.of(
                new Object[]{"BE", 0.9},
                new Object[]{"BE", 0.8},
                new Object[]{"BE", 0.7}
        );
        SliReport report = service.buildReport("BE", List.of(), List.of(), rewards);
        assertThat(report.findByName("quality").value()).isEqualTo(1.0);
    }

    private List<PlanItem> makeItems(int count) {
        List<PlanItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(makeItem());
        }
        return items;
    }

    private PlanItem makeItem() {
        return new PlanItem(UUID.randomUUID(), 1, "T1", "title", "desc",
                            WorkerType.BE, "be-java", List.of(), List.of());
    }
}
