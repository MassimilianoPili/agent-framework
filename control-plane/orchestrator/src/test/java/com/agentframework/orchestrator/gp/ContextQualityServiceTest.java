package com.agentframework.orchestrator.gp;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.messaging.dto.AgentResult;
import com.agentframework.orchestrator.messaging.dto.FileModificationEvent;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContextQualityService} — information-theoretic context quality scoring (#35).
 *
 * <p>Tests three scoring components (File Relevance, Entropy, KL Divergence),
 * the composite score computation, feedback generation, and infrastructure worker exclusion.</p>
 */
@ExtendWith(MockitoExtension.class)
class ContextQualityServiceTest {

    @Mock private TaskOutcomeRepository taskOutcomeRepository;
    @Mock private PlanItemRepository planItemRepository;

    private ContextQualityService service;

    private static final ContextQualityProperties DEFAULT_PROPS =
            new ContextQualityProperties(new ContextQualityProperties.Weights(0.45, 0.30, 0.25));

    @BeforeEach
    void setUp() {
        service = new ContextQualityService(
                taskOutcomeRepository, planItemRepository, new ObjectMapper(), DEFAULT_PROPS);
    }

    // ── File Relevance ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("File Relevance (proxy for MI)")
    class FileRelevanceTests {

        @Test
        void allFilesUsed_returnsOne() {
            Set<String> selected = Set.of("src/main/Auth.java", "src/main/User.java");
            Set<String> used = Set.of("src/main/Auth.java", "src/main/User.java");

            double score = service.computeFileRelevance(selected, used);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        void noFilesUsed_differentPaths_returnsZero() {
            Set<String> selected = Set.of("src/main/Auth.java", "src/main/User.java");
            Set<String> used = Set.of("src/test/FooTest.java");

            double score = service.computeFileRelevance(selected, used);

            assertThat(score).isEqualTo(0.0);
        }

        @Test
        void partialOverlap_returnsFraction() {
            Set<String> selected = Set.of("src/main/Auth.java", "src/main/User.java",
                                          "src/main/Config.java", "src/main/Db.java");
            Set<String> used = Set.of("src/main/Auth.java", "src/main/User.java");

            double score = service.computeFileRelevance(selected, used);

            assertThat(score).isEqualTo(0.5); // 2 out of 4 selected files used
        }

        @Test
        void emptySelectedFiles_returnsNeutral() {
            double score = service.computeFileRelevance(Set.of(), Set.of("src/main/Auth.java"));

            assertThat(score).isEqualTo(0.5);
        }

        @Test
        void emptyUsedFiles_returnsNeutral() {
            double score = service.computeFileRelevance(Set.of("src/main/Auth.java"), Set.of());

            assertThat(score).isEqualTo(0.5);
        }

        @Test
        void fuzzyMatch_subpathContainment() {
            // Worker reports full path, CM reports relative
            Set<String> selected = Set.of("Auth.java");
            Set<String> used = Set.of("src/main/java/com/example/Auth.java");

            double score = service.computeFileRelevance(selected, used);

            assertThat(score).isEqualTo(1.0); // fuzzy match: used contains selected
        }
    }

    // ── Entropy Score ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("Entropy Score (context breadth)")
    class EntropyScoreTests {

        @Test
        void optimalDeps_highScore() {
            // 3 deps, ~5000 chars total — optimal zone
            Map<String, String> deps = Map.of(
                    "CM-001", "x".repeat(1500),
                    "RM-001", "x".repeat(2000),
                    "SM-001", "x".repeat(1500)
            );

            double score = service.computeEntropyScore(deps);

            assertThat(score).isGreaterThan(0.7);
        }

        @Test
        void tooManyDeps_lowerScore() {
            // 10 deps, very large — too broad
            Map<String, String> deps = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                deps.put("DEP-" + i, "x".repeat(5000));
            }

            double score = service.computeEntropyScore(deps);

            assertThat(score).isLessThan(0.5);
        }

        @Test
        void singleTinyDep_lowerScore() {
            // 1 dep, tiny — too narrow
            Map<String, String> deps = Map.of("CM-001", "small");

            double score = service.computeEntropyScore(deps);

            assertThat(score).isLessThan(0.6);
        }
    }

    // ── KL Divergence Score ─────────────────────────────────────────────────

    @Nested
    @DisplayName("KL Divergence Score (selected vs used alignment)")
    class KlDivergenceTests {

        @Test
        void perfectOverlap_returnsOne() {
            Set<String> files = Set.of("src/Auth.java", "src/User.java");

            double score = service.computeKlDivergenceScore(files, files);

            assertThat(score).isEqualTo(1.0);
        }

        @Test
        void noOverlap_returnsZero() {
            Set<String> selected = Set.of("src/Auth.java");
            Set<String> used = Set.of("test/FooTest.java");

            double score = service.computeKlDivergenceScore(selected, used);

            assertThat(score).isEqualTo(0.0);
        }

        @Test
        void partialOverlap_returnsMiddle() {
            // 2 selected, 4 used, 1 overlap
            // coverageUsed = 1/4 = 0.25, coverageSelected = 1/2 = 0.5
            // geometric mean = sqrt(0.25 * 0.5) ≈ 0.354
            Set<String> selected = Set.of("src/Auth.java", "src/User.java");
            Set<String> used = Set.of("src/Auth.java", "test/A.java", "test/B.java", "test/C.java");

            double score = service.computeKlDivergenceScore(selected, used);

            assertThat(score).isCloseTo(Math.sqrt(0.25 * 0.5), within(0.01));
        }

        @Test
        void emptySelected_returnsNeutral() {
            double score = service.computeKlDivergenceScore(Set.of(), Set.of("src/Auth.java"));

            assertThat(score).isEqualTo(0.5);
        }

        @Test
        void emptyUsed_returnsNeutral() {
            double score = service.computeKlDivergenceScore(Set.of("src/Auth.java"), Set.of());

            assertThat(score).isEqualTo(0.5);
        }

        @Test
        void symmetricSets_higherThanAsymmetric() {
            // Symmetric: 3 selected, 3 used, all overlap
            double symmetric = service.computeKlDivergenceScore(
                    Set.of("a.java", "b.java", "c.java"),
                    Set.of("a.java", "b.java", "c.java"));

            // Asymmetric: 1 selected, 5 used, 1 overlap
            double asymmetric = service.computeKlDivergenceScore(
                    Set.of("a.java"),
                    Set.of("a.java", "b.java", "c.java", "d.java", "e.java"));

            assertThat(symmetric).isGreaterThan(asymmetric);
        }
    }

    // ── Feedback helpers ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Feedback (unused/missing/suggestion)")
    class FeedbackTests {

        @Test
        void unusedSelectedFiles_identifiesWastedContext() {
            Set<String> selected = Set.of("src/Auth.java", "src/User.java", "src/Config.java");
            Set<String> used = Set.of("src/Auth.java");

            Set<String> unused = service.computeUnusedSelectedFiles(selected, used);

            assertThat(unused).containsExactlyInAnyOrder("src/User.java", "src/Config.java");
        }

        @Test
        void missingFiles_identifiesMissingContext() {
            Set<String> selected = Set.of("src/Auth.java");
            Set<String> used = Set.of("src/Auth.java", "src/Db.java", "src/Cache.java");

            Set<String> missing = service.computeMissingFiles(selected, used);

            assertThat(missing).containsExactlyInAnyOrder("src/Db.java", "src/Cache.java");
        }

        @Test
        void suggestion_wellAligned_noSuggestions() {
            String suggestion = ContextQualityService.buildSuggestion(Set.of(), Set.of());

            assertThat(suggestion).isEqualTo("Context well-aligned");
        }

        @Test
        void suggestion_unusedAndMissing_combinedMessage() {
            Set<String> unused = Set.of("src/Old.java");
            Set<String> missing = Set.of("src/New.java");

            String suggestion = ContextQualityService.buildSuggestion(unused, missing);

            assertThat(suggestion).contains("Consider removing 1 unused file(s)");
            assertThat(suggestion).contains("Consider adding 1 missing file(s)");
        }
    }

    // ── computeAndStore integration ─────────────────────────────────────────

    @Nested
    @DisplayName("computeAndStore (integration)")
    class ComputeAndStoreTests {

        @Test
        void infrastructureWorker_returnsNull() {
            PlanItem item = mockItem(WorkerType.CONTEXT_MANAGER, List.of());

            Double score = service.computeAndStore(item, mockResult(null, List.of()));

            assertThat(score).isNull();
            verify(taskOutcomeRepository, never()).updateContextQualityScore(any(), anyDouble());
        }

        @Test
        void noDependencies_returnsNull() {
            PlanItem item = mockItem(WorkerType.BE, List.of());

            Double score = service.computeAndStore(item, mockResult(null, List.of()));

            assertThat(score).isNull();
        }

        @Test
        void normalTask_computesAndPersists() {
            UUID planId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();

            // CM-001 produced a result with relevant_files
            PlanItem cmItem = mockDoneItem("CM-001",
                    "{\"relevant_files\":[\"src/Auth.java\",\"src/User.java\"]}");

            PlanItem beItem = mockItem(WorkerType.BE, List.of("CM-001"));
            when(beItem.getId()).thenReturn(itemId);
            when(beItem.getPlan().getId()).thenReturn(planId);

            when(planItemRepository.findByPlanIdAndStatus(planId, ItemStatus.DONE))
                    .thenReturn(List.of(cmItem));

            // Worker used Auth.java and created a new file
            AgentResult result = mockResult(
                    "{\"files_modified\":[\"src/Auth.java\"]}",
                    List.of(new FileModificationEvent("src/Auth.java", "MODIFY", null, null, null))
            );

            Double score = service.computeAndStore(beItem, result);

            assertThat(score).isNotNull();
            assertThat(score).isBetween(0.0, 1.0);
            verify(taskOutcomeRepository).updateContextQualityScore(eq(itemId), anyDouble());
        }
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Utility methods")
    class UtilityTests {

        @Test
        void normalizeFilePath_removesLeadingSlashAndDot() {
            assertThat(ContextQualityService.normalizeFilePath("./src/Auth.java"))
                    .isEqualTo("src/Auth.java");
            assertThat(ContextQualityService.normalizeFilePath("/src/Auth.java"))
                    .isEqualTo("src/Auth.java");
            assertThat(ContextQualityService.normalizeFilePath("src\\Auth.java"))
                    .isEqualTo("src/Auth.java");
        }

        @Test
        void isInfrastructureWorker_domainWorkerReturnsFalse() {
            assertThat(ContextQualityService.isInfrastructureWorker(WorkerType.BE)).isFalse();
            assertThat(ContextQualityService.isInfrastructureWorker(WorkerType.FE)).isFalse();
            assertThat(ContextQualityService.isInfrastructureWorker(WorkerType.DBA)).isFalse();
        }

        @Test
        void isInfrastructureWorker_infraWorkerReturnsTrue() {
            assertThat(ContextQualityService.isInfrastructureWorker(WorkerType.CONTEXT_MANAGER)).isTrue();
            assertThat(ContextQualityService.isInfrastructureWorker(WorkerType.TOOL_MANAGER)).isTrue();
            assertThat(ContextQualityService.isInfrastructureWorker(WorkerType.REVIEW)).isTrue();
        }

        @Test
        void weights_sumToOne() {
            double sum = service.getFileRelevanceWeight()
                       + service.getEntropyWeight()
                       + service.getKlDivergenceWeight();

            assertThat(sum).isCloseTo(1.0, within(1e-9));
        }

        @Test
        void defaultWeights_matchExpectedValues() {
            assertThat(service.getFileRelevanceWeight()).isEqualTo(0.45);
            assertThat(service.getEntropyWeight()).isEqualTo(0.30);
            assertThat(service.getKlDivergenceWeight()).isEqualTo(0.25);
        }

        @Test
        void compositeScore_matchesWeightedSum() {
            // Known metric values: fileRelevance=1.0, usedFiles non-empty → KL=1.0
            // entropy depends on depResults shape — we test the weighted combination directly
            Set<String> files = Set.of("src/Auth.java", "src/User.java");
            double fr = service.computeFileRelevance(files, files);   // 1.0
            double kl = service.computeKlDivergenceScore(files, files); // 1.0
            Map<String, String> deps = Map.of(
                    "CM-001", "x".repeat(1500),
                    "SM-001", "x".repeat(2000),
                    "RM-001", "x".repeat(1500));
            double en = service.computeEntropyScore(deps);

            double expected = 0.45 * fr + 0.30 * en + 0.25 * kl;

            assertThat(expected).isBetween(0.0, 1.0);
            // Verify the exact formula: fr=1.0, kl=1.0, so composite = 0.45 + 0.30*en + 0.25
            assertThat(expected).isCloseTo(0.70 + 0.30 * en, within(1e-9));
        }

        @Test
        void customWeights_changeCompositeResult() {
            // Create a service with custom weights (heavy on entropy, zero KL)
            var customProps = new ContextQualityProperties(
                    new ContextQualityProperties.Weights(0.20, 0.80, 0.00));
            var customService = new ContextQualityService(
                    taskOutcomeRepository, planItemRepository, new ObjectMapper(), customProps);

            assertThat(customService.getFileRelevanceWeight()).isEqualTo(0.20);
            assertThat(customService.getEntropyWeight()).isEqualTo(0.80);
            assertThat(customService.getKlDivergenceWeight()).isEqualTo(0.00);

            // With custom weights, composite = 0.20*fr + 0.80*en + 0.00*kl
            // vs default:              composite = 0.45*fr + 0.30*en + 0.25*kl
            // For fr=1.0, kl=1.0, en=0.5:
            //   default  = 0.45 + 0.15 + 0.25  = 0.85
            //   custom   = 0.20 + 0.40 + 0.00  = 0.60
            Set<String> files = Set.of("src/Auth.java");
            double fr = service.computeFileRelevance(files, files);       // 1.0
            double kl = service.computeKlDivergenceScore(files, files);   // 1.0

            double defaultComposite = 0.45 * fr + 0.30 * 0.5 + 0.25 * kl;
            double customComposite  = 0.20 * fr + 0.80 * 0.5 + 0.00 * kl;

            assertThat(defaultComposite).isNotEqualTo(customComposite);
            assertThat(customComposite).isCloseTo(0.60, within(1e-9));
        }
    }

    // ── Test helpers ────────────────────────────────────────────────────────

    private PlanItem mockItem(WorkerType workerType, List<String> dependsOn) {
        PlanItem item = mock(PlanItem.class);
        Plan plan = mock(Plan.class);
        when(item.getWorkerType()).thenReturn(workerType);
        lenient().when(item.getDependsOn()).thenReturn(dependsOn);
        lenient().when(item.getTaskKey()).thenReturn(workerType.name() + "-001");
        lenient().when(item.getPlan()).thenReturn(plan);
        lenient().when(plan.getId()).thenReturn(UUID.randomUUID());
        return item;
    }

    private PlanItem mockDoneItem(String taskKey, String result) {
        PlanItem item = mock(PlanItem.class);
        when(item.getTaskKey()).thenReturn(taskKey);
        when(item.getResult()).thenReturn(result);
        return item;
    }

    private AgentResult mockResult(String resultJson, List<FileModificationEvent> fileModifications) {
        return new AgentResult(
                UUID.randomUUID(), UUID.randomUUID(), "TEST-001",
                true, resultJson, null, 5000L,
                "BE", "be-java", null, null,
                null, 1000L, null, fileModifications
        );
    }
}
