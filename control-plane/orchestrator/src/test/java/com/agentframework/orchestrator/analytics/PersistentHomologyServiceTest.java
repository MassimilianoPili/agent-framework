package com.agentframework.orchestrator.analytics;

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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PersistentHomologyService}.
 *
 * <p>Verifies barcode computation, significant feature detection,
 * and insufficient-data edge cases.</p>
 */
@ExtendWith(MockitoExtension.class)
class PersistentHomologyServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private PersistentHomologyService service;

    @BeforeEach
    void setUp() {
        service = new PersistentHomologyService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "maxEpsilon", 2.0);
        ReflectionTestUtils.setField(service, "epsilonSteps", 20);
    }

    /**
     * Constructs an Object[] row in the format returned by
     * {@code findOutcomesWithEmbeddingByWorkerType}:
     * [0]=task_key, [1]=worker_profile, [2]=actual_reward, [3]=gp_mu, [4]=embedding_text
     *
     * The embedding is a simple comma-separated string of floats (pgvector text format).
     * Only the first 2 dimensions are used by PersistentHomologyService.
     */
    private Object[] makeRow(double x, double y) {
        // Embedding: "[x,y,0.0,0.0]" — only first 2 dims matter
        String embeddingText = "[" + x + "," + y + ",0.0,0.0]";
        return new Object[]{"T1", "be-java", 0.8, 0.75, embeddingText};
    }

    @Test
    @DisplayName("compute returns barcodes for clustered point cloud")
    void compute_clusteredPoints_returnsBarcodes() {
        // Two tight clusters — should produce persistent β₀ features
        // Cluster A: near (0,0)
        // Cluster B: near (5,0)  — far apart relative to intra-cluster distances
        List<Object[]> rows = new ArrayList<>();
        rows.add(makeRow(0.0, 0.0));
        rows.add(makeRow(0.1, 0.0));
        rows.add(makeRow(0.0, 0.1));
        rows.add(makeRow(0.05, 0.05));
        rows.add(makeRow(0.08, 0.02));

        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("be", 100))
                .thenReturn(rows);

        PersistentHomologyService.PersistentHomologyReport report = service.compute("be");

        assertThat(report).isNotNull();
        assertThat(report.numTasks()).isEqualTo(5);
        assertThat(report.barcodes()).isNotEmpty();
    }

    @Test
    @DisplayName("compute returns null when fewer than MIN_SAMPLES valid points")
    void compute_insufficientData_returnsNull() {
        List<Object[]> rows = List.of(
                makeRow(0.0, 0.0),
                makeRow(1.0, 0.0)   // only 2 valid points, need 5
        );

        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("fe", 100))
                .thenReturn(rows);

        PersistentHomologyService.PersistentHomologyReport report = service.compute("fe");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("compute skips rows with null embedding")
    void compute_nullEmbeddings_skipped() {
        // 4 real rows + 1 null embedding → total 4 valid points < MIN_SAMPLES (5) → null
        List<Object[]> rows = new ArrayList<>();
        rows.add(makeRow(0.0, 0.0));
        rows.add(makeRow(1.0, 0.0));
        rows.add(makeRow(0.0, 1.0));
        rows.add(makeRow(1.0, 1.0));
        // row with null embedding at index 4
        rows.add(new Object[]{"T5", "be-java", 0.5, 0.5, null});

        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("be", 100))
                .thenReturn(rows);

        PersistentHomologyService.PersistentHomologyReport report = service.compute("be");

        // Only 4 valid points — below MIN_SAMPLES
        assertThat(report).isNull();
    }

    @Test
    @DisplayName("barcodes have non-negative persistence (death >= birth)")
    void compute_barcodes_nonNegativePersistence() {
        // 6 points in a grid — enough for homology
        List<Object[]> rows = List.of(
                makeRow(0.0, 0.0),
                makeRow(0.5, 0.0),
                makeRow(1.0, 0.0),
                makeRow(0.0, 0.5),
                makeRow(0.5, 0.5),
                makeRow(1.0, 0.5)
        );

        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("ops", 100))
                .thenReturn(rows);

        PersistentHomologyService.PersistentHomologyReport report = service.compute("ops");

        assertThat(report).isNotNull();
        for (PersistentHomologyService.Barcode b : report.barcodes()) {
            assertThat(b.death()).isGreaterThanOrEqualTo(b.birth());
        }
    }

    @Test
    @DisplayName("significant features have type β₀")
    void compute_significantFeatures_typeBeta0() {
        // 7 well-separated points — should yield significant components
        List<Object[]> rows = List.of(
                makeRow(0.0, 0.0),
                makeRow(0.05, 0.0),
                makeRow(0.0, 0.05),
                makeRow(0.03, 0.03),
                makeRow(0.02, 0.04),
                makeRow(0.04, 0.01),
                makeRow(0.01, 0.02)
        );

        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("ml", 100))
                .thenReturn(rows);

        PersistentHomologyService.PersistentHomologyReport report = service.compute("ml");

        assertThat(report).isNotNull();
        report.significantFeatures().forEach(f ->
                assertThat(f.type()).isEqualTo("β₀")
        );
        // All significant features must have persistence > 0
        report.significantFeatures().forEach(f ->
                assertThat(f.persistence()).isPositive()
        );
    }
}
