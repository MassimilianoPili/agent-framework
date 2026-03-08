package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.InformationBottleneckService.IBCompressionReport;
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
 * Unit tests for {@link InformationBottleneckService}.
 *
 * <p>Verifies IB compression report for synthetic low-dimensional embeddings,
 * empty data, and embedding parsing.</p>
 */
@ExtendWith(MockitoExtension.class)
class InformationBottleneckServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;

    private InformationBottleneckService service;

    @BeforeEach
    void setUp() {
        service = new InformationBottleneckService(taskOutcomeRepository);
        ReflectionTestUtils.setField(service, "compressionRatio", 0.5);  // 4 → 2 dims in tests
        ReflectionTestUtils.setField(service, "beta", 1.0);
    }

    /**
     * Creates a row matching findOutcomesWithEmbeddingByWorkerType format.
     * [0]=task_key, [1]=worker_profile, [2]=actual_reward, [3]=gp_mu, [4]=embedding_text
     */
    private Object[] makeEmbeddingRow(double reward, double[] emb) {
        String embText = "[" + join(emb) + "]";
        return new Object[]{"task-1", "be-java", reward, reward, embText};
    }

    private String join(double[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("compress returns valid IB report for synthetic embeddings")
    void compress_validData_returnsReport() {
        // 4-dim embeddings, compression-ratio=0.5 → 2 dims
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double t = i / 10.0;
            rows.add(makeEmbeddingRow(0.5 + 0.3 * Math.sin(t),
                                      new double[]{t, t * 0.5, t * 0.2, 1 - t}));
        }
        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("BE", 200))
                .thenReturn(rows);

        IBCompressionReport report = service.compress("BE");

        assertThat(report).isNotNull();
        assertThat(report.originalDim()).isEqualTo(4);
        assertThat(report.compressedDim()).isEqualTo(2);
        assertThat(report.beta()).isEqualTo(1.0);
        assertThat(report.mutualInfoZX()).isBetween(0.0, 1.0);
        assertThat(report.mutualInfoZY()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("compress with insufficient data returns null")
    void compress_insufficientData_returnsNull() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(makeEmbeddingRow(0.8, new double[]{0.1, 0.2, 0.3, 0.4}));
        }
        when(taskOutcomeRepository.findOutcomesWithEmbeddingByWorkerType("BE", 200))
                .thenReturn(rows);

        IBCompressionReport report = service.compress("BE");

        assertThat(report).isNull();
    }

    @Test
    @DisplayName("parseEmbedding correctly parses pgvector text format")
    void parseEmbedding_parsesCorrectly() {
        double[] result = InformationBottleneckService.parseEmbedding("[0.1,0.2,0.3]");

        assertThat(result).hasSize(3);
        assertThat(result[0]).isCloseTo(0.1, within(1e-9));
        assertThat(result[1]).isCloseTo(0.2, within(1e-9));
        assertThat(result[2]).isCloseTo(0.3, within(1e-9));
    }

    @Test
    @DisplayName("parseEmbedding returns null for blank input")
    void parseEmbedding_blankInput_returnsNull() {
        assertThat(InformationBottleneckService.parseEmbedding(null)).isNull();
        assertThat(InformationBottleneckService.parseEmbedding("")).isNull();
    }
}
