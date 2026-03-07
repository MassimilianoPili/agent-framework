package com.agentframework.orchestrator.gp;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SerendipityService}.
 *
 * <p>Covers both phases: collection (on task completion) and query (at dispatch time).</p>
 */
@ExtendWith(MockitoExtension.class)
class SerendipityServiceTest {

    @Mock TaskOutcomeService taskOutcomeService;
    @Mock TaskOutcomeRepository taskOutcomeRepository;
    @Mock ContextFileOutcomeRepository fileOutcomeRepository;
    @Mock PlanItemRepository planItemRepository;

    private SerendipityService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID PLAN_ID = UUID.randomUUID();
    private static final UUID TASK_OUTCOME_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SerendipityService(
                taskOutcomeService, taskOutcomeRepository,
                fileOutcomeRepository, planItemRepository, objectMapper);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private PlanItem buildDomainItem(String taskKey, WorkerType type, String profile,
                                      float reward, List<String> dependsOn) {
        PlanItem item = new PlanItem(
                UUID.randomUUID(), 1, taskKey, "Title " + taskKey,
                "Description for " + taskKey, type, profile, dependsOn, List.of());
        Plan plan = new Plan(PLAN_ID, "test spec");
        plan.addItem(item);
        item.forceStatus(ItemStatus.DONE);
        item.setAggregatedReward(reward);
        return item;
    }

    private PlanItem buildCmItem(String taskKey, String resultJson) {
        PlanItem item = new PlanItem(
                UUID.randomUUID(), 0, taskKey, "Context for " + taskKey,
                "Find files", WorkerType.CONTEXT_MANAGER, null, List.of(), List.of());
        item.forceStatus(ItemStatus.DONE);
        item.setResult(resultJson);
        return item;
    }

    private String cmResultJson(String... filePaths) {
        var files = new ArrayList<Map<String, String>>();
        for (String path : filePaths) {
            files.add(Map.of("path", path, "reason", "test reason"));
        }
        try {
            return objectMapper.writeValueAsString(Map.of("relevant_files", files));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── Collection: collectFileOutcomes ─────────────────────────────────────────

    @Nested
    @DisplayName("collectFileOutcomes")
    class CollectTests {

        @Test
        @DisplayName("domain worker with high residual saves files")
        void collect_domainWorkerWithHighResidual_savesFiles() {
            PlanItem domain = buildDomainItem("BE-001", WorkerType.BE, "be-java", 0.9f, List.of("CM-001"));
            PlanItem cm = buildCmItem("CM-001", cmResultJson("src/Main.java", "src/Config.java"));

            // task_outcome with gp_mu=0.4, actual_reward=0.9 → residual=0.5
            when(taskOutcomeRepository.findOutcomeByPlanItemId(domain.getId()))
                    .thenReturn(List.<Object[]>of(new Object[]{TASK_OUTCOME_ID, 0.4, 0.9}));
            when(fileOutcomeRepository.existsByTaskOutcomeId(TASK_OUTCOME_ID))
                    .thenReturn(false);
            when(planItemRepository.findByPlanId(PLAN_ID))
                    .thenReturn(List.of(domain, cm));

            service.collectFileOutcomes(domain);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ContextFileOutcome>> captor = ArgumentCaptor.forClass(List.class);
            verify(fileOutcomeRepository).saveAll(captor.capture());
            List<ContextFileOutcome> saved = captor.getValue();

            assertThat(saved).hasSize(2);
            assertThat(saved).extracting(ContextFileOutcome::getFilePath)
                    .containsExactlyInAnyOrder("src/Main.java", "src/Config.java");
            assertThat(saved).allMatch(cfo -> cfo.getResidual() == 0.5f);
            assertThat(saved).allMatch(cfo -> cfo.getTaskOutcomeId().equals(TASK_OUTCOME_ID));
        }

        @Test
        @DisplayName("low residual does not save files")
        void collect_lowResidual_noFilesSaved() {
            PlanItem domain = buildDomainItem("BE-002", WorkerType.BE, "be-java", 0.5f, List.of("CM-002"));

            // gp_mu=0.45, actual=0.5 → residual=0.05 < 0.15 threshold
            when(taskOutcomeRepository.findOutcomeByPlanItemId(domain.getId()))
                    .thenReturn(List.<Object[]>of(new Object[]{TASK_OUTCOME_ID, 0.45, 0.5}));

            service.collectFileOutcomes(domain);

            verify(fileOutcomeRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("non-domain worker type is skipped")
        void collect_nonDomainWorker_skipped() {
            PlanItem cm = buildDomainItem("CM-003", WorkerType.CONTEXT_MANAGER, null, 0.9f, List.of());

            service.collectFileOutcomes(cm);

            verifyNoInteractions(taskOutcomeRepository);
            verify(fileOutcomeRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("no GP outcome recorded skips collection")
        void collect_noGpOutcome_skipped() {
            PlanItem domain = buildDomainItem("BE-004", WorkerType.BE, "be-java", 0.8f, List.of("CM-004"));

            when(taskOutcomeRepository.findOutcomeByPlanItemId(domain.getId()))
                    .thenReturn(List.of());

            service.collectFileOutcomes(domain);

            verify(fileOutcomeRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("already collected is skipped (idempotency)")
        void collect_alreadyCollected_skipped() {
            PlanItem domain = buildDomainItem("BE-005", WorkerType.BE, "be-java", 0.9f, List.of("CM-005"));

            when(taskOutcomeRepository.findOutcomeByPlanItemId(domain.getId()))
                    .thenReturn(List.<Object[]>of(new Object[]{TASK_OUTCOME_ID, 0.3, 0.9}));
            when(fileOutcomeRepository.existsByTaskOutcomeId(TASK_OUTCOME_ID))
                    .thenReturn(true); // already collected

            service.collectFileOutcomes(domain);

            verify(fileOutcomeRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("no CM dependency results in no files saved")
        void collect_noCmDependency_noFilesSaved() {
            // Domain item depends on "SM-001" (schema manager, not CM)
            PlanItem domain = buildDomainItem("BE-006", WorkerType.BE, "be-java", 0.9f, List.of("SM-001"));
            PlanItem schema = new PlanItem(
                    UUID.randomUUID(), 0, "SM-001", "Schema",
                    "Desc", WorkerType.SCHEMA_MANAGER, null, List.of(), List.of());
            schema.forceStatus(ItemStatus.DONE);
            schema.setResult("{}");

            when(taskOutcomeRepository.findOutcomeByPlanItemId(domain.getId()))
                    .thenReturn(List.<Object[]>of(new Object[]{TASK_OUTCOME_ID, 0.3, 0.9}));
            when(fileOutcomeRepository.existsByTaskOutcomeId(TASK_OUTCOME_ID))
                    .thenReturn(false);
            when(planItemRepository.findByPlanId(PLAN_ID))
                    .thenReturn(List.of(domain, schema));

            service.collectFileOutcomes(domain);

            verify(fileOutcomeRepository, never()).saveAll(anyList());
        }
    }

    // ── Query: getSerendipityHints ──────────────────────────────────────────────

    @Nested
    @DisplayName("getSerendipityHints")
    class HintTests {

        @Test
        @DisplayName("similar tasks with files returns ranked hints")
        void hints_similarTasksWithFiles_returnsRanked() {
            UUID outcomeId1 = UUID.randomUUID();
            UUID outcomeId2 = UUID.randomUUID();

            when(taskOutcomeService.embedTask("Build API", "REST endpoint"))
                    .thenReturn(new float[]{0.1f, 0.2f});
            when(taskOutcomeRepository.findSimilarOutcomes(anyString(), eq(20)))
                    .thenReturn(List.of(
                            new Object[]{outcomeId1, PLAN_ID, "BE-1", "BE", "be-java", 0.3, 0.9, 0.95},
                            new Object[]{outcomeId2, PLAN_ID, "BE-2", "BE", "be-java", 0.4, 0.85, 0.80}
                    ));
            when(fileOutcomeRepository.findByOutcomeIdsAndMinResidual(anyCollection(), eq(0.15f)))
                    .thenReturn(List.of(
                            new ContextFileOutcome(UUID.randomUUID(), outcomeId1, PLAN_ID, "BE-1", "SecurityConfig.java", 0.5f),
                            new ContextFileOutcome(UUID.randomUUID(), outcomeId2, PLAN_ID, "BE-2", "DatabaseInit.java", 0.3f)
                    ));

            List<SerendipityService.SerendipityHint> hints = service.getSerendipityHints("Build API", "REST endpoint");

            assertThat(hints).hasSize(2);
            // SecurityConfig: 0.95 * 0.5 = 0.475
            // DatabaseInit: 0.80 * 0.3 = 0.24
            assertThat(hints.get(0).filePath()).isEqualTo("SecurityConfig.java");
            assertThat(hints.get(0).score()).isCloseTo(0.475, org.assertj.core.data.Offset.offset(0.01));
            assertThat(hints.get(1).filePath()).isEqualTo("DatabaseInit.java");
        }

        @Test
        @DisplayName("no similar tasks returns empty list")
        void hints_noSimilarTasks_emptyList() {
            when(taskOutcomeService.embedTask(anyString(), anyString()))
                    .thenReturn(new float[]{0.1f});
            when(taskOutcomeRepository.findSimilarOutcomes(anyString(), eq(20)))
                    .thenReturn(List.of());

            List<SerendipityService.SerendipityHint> hints = service.getSerendipityHints("New task", "desc");

            assertThat(hints).isEmpty();
        }

        @Test
        @DisplayName("same file from multiple tasks aggregates scores")
        void hints_aggregatesSameFile_sumScores() {
            UUID outcomeId1 = UUID.randomUUID();
            UUID outcomeId2 = UUID.randomUUID();

            when(taskOutcomeService.embedTask(anyString(), anyString()))
                    .thenReturn(new float[]{0.1f});
            when(taskOutcomeRepository.findSimilarOutcomes(anyString(), eq(20)))
                    .thenReturn(List.of(
                            new Object[]{outcomeId1, PLAN_ID, "BE-1", "BE", "be-java", 0.3, 0.9, 0.90},
                            new Object[]{outcomeId2, PLAN_ID, "BE-2", "BE", "be-java", 0.4, 0.85, 0.85}
                    ));
            // Same file "Shared.java" appears in both task outcomes
            when(fileOutcomeRepository.findByOutcomeIdsAndMinResidual(anyCollection(), eq(0.15f)))
                    .thenReturn(List.of(
                            new ContextFileOutcome(UUID.randomUUID(), outcomeId1, PLAN_ID, "BE-1", "Shared.java", 0.4f),
                            new ContextFileOutcome(UUID.randomUUID(), outcomeId2, PLAN_ID, "BE-2", "Shared.java", 0.3f)
                    ));

            List<SerendipityService.SerendipityHint> hints = service.getSerendipityHints("Task", "desc");

            assertThat(hints).hasSize(1);
            // Aggregated: (0.90 * 0.4) + (0.85 * 0.3) = 0.36 + 0.255 = 0.615
            assertThat(hints.get(0).filePath()).isEqualTo("Shared.java");
            assertThat(hints.get(0).score()).isCloseTo(0.615, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("limits to MAX_SERENDIPITY_FILES")
        void hints_limitsToMaxFiles() {
            UUID outcomeId = UUID.randomUUID();

            when(taskOutcomeService.embedTask(anyString(), anyString()))
                    .thenReturn(new float[]{0.1f});
            when(taskOutcomeRepository.findSimilarOutcomes(anyString(), eq(20)))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{outcomeId, PLAN_ID, "BE-1", "BE", "be-java", 0.3, 0.9, 0.90}
                    ));
            // Return 8 different files, but MAX_SERENDIPITY_FILES = 5
            List<ContextFileOutcome> manyFiles = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                manyFiles.add(new ContextFileOutcome(
                        UUID.randomUUID(), outcomeId, PLAN_ID, "BE-1",
                        "File" + i + ".java", 0.5f - i * 0.01f));
            }
            when(fileOutcomeRepository.findByOutcomeIdsAndMinResidual(anyCollection(), eq(0.15f)))
                    .thenReturn(manyFiles);

            List<SerendipityService.SerendipityHint> hints = service.getSerendipityHints("Task", "desc");

            assertThat(hints).hasSize(SerendipityService.MAX_SERENDIPITY_FILES);
        }
    }

    // ── JSON parsing ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseRelevantFiles")
    class ParseTests {

        @Test
        @DisplayName("valid CM result JSON extracts file paths")
        void parse_validJson_extractsPaths() {
            String json = cmResultJson("src/Main.java", "src/Config.java");
            List<String> paths = service.parseRelevantFiles(json);
            assertThat(paths).containsExactly("src/Main.java", "src/Config.java");
        }

        @Test
        @DisplayName("invalid JSON returns empty list")
        void parse_invalidJson_emptyList() {
            List<String> paths = service.parseRelevantFiles("not valid json");
            assertThat(paths).isEmpty();
        }

        @Test
        @DisplayName("missing relevant_files key returns empty list")
        void parse_missingKey_emptyList() {
            List<String> paths = service.parseRelevantFiles("{\"world_state\": \"ok\"}");
            assertThat(paths).isEmpty();
        }
    }
}
