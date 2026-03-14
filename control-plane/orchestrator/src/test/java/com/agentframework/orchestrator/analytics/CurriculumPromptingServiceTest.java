package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.CurriculumPromptingService.CurriculumSelection;
import com.agentframework.orchestrator.analytics.CurriculumPromptingService.GoldenExample;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CurriculumPromptingService}.
 *
 * <p>Verifies curriculum-based exemplar selection (easy → medium → similar),
 * registry operations, deduplication, and capacity eviction.</p>
 */
class CurriculumPromptingServiceTest {

    private CurriculumPromptingService service;

    @BeforeEach
    void setUp() {
        service = new CurriculumPromptingService();
        ReflectionTestUtils.setField(service, "maxExamplesPerType", 5);
        ReflectionTestUtils.setField(service, "selectionK", 3);
    }

    // --- Empty registry ---

    @Test
    @DisplayName("selectExemplars on empty registry returns empty selection")
    void selectExemplars_emptyRegistry() {
        CurriculumSelection result = service.selectExemplars("BE", "task", 0.5, 3);

        assertThat(result.selected()).isEmpty();
        assertThat(result.rationale()).contains("No golden examples");
    }

    // --- Selection phases ---

    @Test
    @DisplayName("selectExemplars always includes the easiest example first")
    void selectExemplars_includesEasiest() {
        service.addExample(new GoldenExample("hard task", "hard solution", 0.9, "BE"));
        service.addExample(new GoldenExample("easy task", "easy solution", 0.1, "BE"));
        service.addExample(new GoldenExample("medium task", "medium solution", 0.5, "BE"));

        CurriculumSelection result = service.selectExemplars("BE", "current", 0.8, 3);

        assertThat(result.selected()).isNotEmpty();
        assertThat(result.selected().get(0).complexity()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("selectExemplars returns results ordered by ascending complexity")
    void selectExemplars_orderedByComplexity() {
        service.addExample(new GoldenExample("t1", "s1", 0.1, "BE"));
        service.addExample(new GoldenExample("t2", "s2", 0.5, "BE"));
        service.addExample(new GoldenExample("t3", "s3", 0.9, "BE"));

        CurriculumSelection result = service.selectExemplars("BE", "current", 0.8, 3);

        double prevComplexity = -1;
        for (GoldenExample e : result.selected()) {
            assertThat(e.complexity()).isGreaterThanOrEqualTo(prevComplexity);
            prevComplexity = e.complexity();
        }
    }

    @Test
    @DisplayName("selectExemplars respects K limit")
    void selectExemplars_respectsK() {
        for (int i = 0; i < 5; i++) {
            service.addExample(new GoldenExample("t" + i, "s" + i, i * 0.2, "BE"));
        }

        CurriculumSelection result = service.selectExemplars("BE", "current", 0.5, 2);

        assertThat(result.selected()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("selectExemplars uses default K when k=0")
    void selectExemplars_defaultK() {
        for (int i = 0; i < 5; i++) {
            service.addExample(new GoldenExample("t" + i, "s" + i, i * 0.2, "BE"));
        }

        // default selectionK = 3
        CurriculumSelection result = service.selectExemplars("BE", "current", 0.5, 0);

        assertThat(result.selected()).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("selectExemplars with single example returns it")
    void selectExemplars_singleExample() {
        service.addExample(new GoldenExample("only", "solution", 0.5, "BE"));

        CurriculumSelection result = service.selectExemplars("BE", "current", 0.5, 3);

        assertThat(result.selected()).hasSize(1);
        assertThat(result.selected().get(0).taskDescription()).isEqualTo("only");
    }

    @Test
    @DisplayName("selectExemplars rationale contains complexity progression")
    void selectExemplars_rationaleFormat() {
        service.addExample(new GoldenExample("t1", "s1", 0.1, "BE"));
        service.addExample(new GoldenExample("t2", "s2", 0.5, "BE"));

        CurriculumSelection result = service.selectExemplars("BE", "current", 0.5, 3);

        assertThat(result.rationale()).contains("BE");
        assertThat(result.rationale()).contains("→");
    }

    // --- Worker type isolation ---

    @Test
    @DisplayName("selectExemplars is partitioned by workerType")
    void selectExemplars_partitionIsolation() {
        service.addExample(new GoldenExample("be-task", "solution", 0.5, "BE"));
        service.addExample(new GoldenExample("fe-task", "solution", 0.5, "FE"));

        CurriculumSelection beResult = service.selectExemplars("BE", "current", 0.5, 3);
        CurriculumSelection feResult = service.selectExemplars("FE", "current", 0.5, 3);

        assertThat(beResult.selected()).hasSize(1);
        assertThat(beResult.selected().get(0).taskDescription()).isEqualTo("be-task");
        assertThat(feResult.selected()).hasSize(1);
        assertThat(feResult.selected().get(0).taskDescription()).isEqualTo("fe-task");
    }

    // --- Add & eviction ---

    @Test
    @DisplayName("addExample evicts oldest when over capacity")
    void addExample_evictsOldest() {
        // maxExamplesPerType = 5
        for (int i = 0; i < 6; i++) {
            service.addExample(new GoldenExample("t" + i, "s" + i, i * 0.1, "BE"));
        }

        assertThat(service.getExampleCount("BE")).isEqualTo(5);
    }

    // --- Deduplication ---

    @Test
    @DisplayName("deduplicateExamples removes near-duplicate complexity entries")
    void deduplicateExamples_removesNearDuplicates() {
        // DEDUP_COMPLEXITY_EPSILON = 0.05
        service.addExample(new GoldenExample("t1", "short", 0.10, "BE"));
        service.addExample(new GoldenExample("t2", "longer solution", 0.12, "BE")); // within epsilon of t1
        service.addExample(new GoldenExample("t3", "other", 0.50, "BE"));           // distinct

        int removed = service.deduplicateExamples("BE");

        assertThat(removed).isEqualTo(1);
        assertThat(service.getExampleCount("BE")).isEqualTo(2);
    }

    @Test
    @DisplayName("deduplicateExamples keeps longer solution when complexity is near-duplicate")
    void deduplicateExamples_keepsLongerSolution() {
        service.addExample(new GoldenExample("t1", "short", 0.10, "BE"));
        service.addExample(new GoldenExample("t2", "much longer solution text", 0.12, "BE"));

        service.deduplicateExamples("BE");

        // After dedup, select the single remaining — it should be the longer solution
        CurriculumSelection result = service.selectExemplars("BE", "test", 0.1, 1);
        assertThat(result.selected()).hasSize(1);
        assertThat(result.selected().get(0).solution()).isEqualTo("much longer solution text");
    }

    @Test
    @DisplayName("deduplicateExamples on empty or missing type returns 0")
    void deduplicateExamples_emptyReturnsZero() {
        assertThat(service.deduplicateExamples("NONEXISTENT")).isZero();
    }

    // --- Registry stats ---

    @Test
    @DisplayName("getRegistryStats returns counts per worker type")
    void getRegistryStats_correctCounts() {
        service.addExample(new GoldenExample("t1", "s1", 0.1, "BE"));
        service.addExample(new GoldenExample("t2", "s2", 0.5, "BE"));
        service.addExample(new GoldenExample("t3", "s3", 0.3, "FE"));

        Map<String, Integer> stats = service.getRegistryStats();

        assertThat(stats).containsEntry("BE", 2);
        assertThat(stats).containsEntry("FE", 1);
    }

    @Test
    @DisplayName("getExampleCount returns 0 for unknown worker type")
    void getExampleCount_unknownType() {
        assertThat(service.getExampleCount("UNKNOWN")).isZero();
    }
}
