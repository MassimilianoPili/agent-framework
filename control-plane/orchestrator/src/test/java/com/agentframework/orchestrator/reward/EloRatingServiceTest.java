package com.agentframework.orchestrator.reward;

import com.agentframework.orchestrator.domain.*;
import com.agentframework.orchestrator.repository.PlanItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EloRatingService} — ELO rating updates based on plan outcomes.
 */
@ExtendWith(MockitoExtension.class)
class EloRatingServiceTest {

    @Mock
    private PlanItemRepository planItemRepository;

    @Mock
    private WorkerEloStatsRepository eloStatsRepository;

    @Captor
    private ArgumentCaptor<WorkerEloStats> statsCaptor;

    private EloRatingService service;

    @BeforeEach
    void setUp() {
        service = new EloRatingService(planItemRepository, eloStatsRepository);
    }

    // ── Two BE profiles: higher reward wins ELO ───────────────────────────────

    @Test
    void updateRatings_twoBEProfiles_higherRewardWinsElo() {
        UUID planId = UUID.randomUUID();

        PlanItem javaItem = doneItem("BE-001", WorkerType.BE, "be-java", 0.8f);
        PlanItem goItem   = doneItem("BE-002", WorkerType.BE, "be-go", 0.3f);

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(javaItem, goItem));
        // Both profiles are new (not in DB)
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());
        when(eloStatsRepository.findById("be-go")).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        // Capture all save calls
        verify(eloStatsRepository, atLeast(4)).save(statsCaptor.capture());

        // Find final ELO states: the last save for each profile is the ELO match result
        List<WorkerEloStats> allSaved = statsCaptor.getAllValues();

        // The last two saves are from the pairwise ELO update (after the recordReward saves)
        WorkerEloStats lastJava = findLastSavedFor(allSaved, "be-java");
        WorkerEloStats lastGo   = findLastSavedFor(allSaved, "be-go");

        // java had higher reward (0.8 > 0.3) → java's ELO should increase above 1600
        assertThat(lastJava.getEloRating()).isGreaterThan(1600.0);
        // go lost → ELO should decrease below 1600
        assertThat(lastGo.getEloRating()).isLessThan(1600.0);
    }

    // ── Single profile per workerType → no ELO update ─────────────────────────

    @Test
    void updateRatings_singleProfilePerType_noEloUpdate() {
        UUID planId = UUID.randomUUID();

        PlanItem beItem = doneItem("BE-001", WorkerType.BE, "be-java", 0.9f);
        PlanItem feItem = doneItem("FE-001", WorkerType.FE, "fe-react", 0.7f);

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(beItem, feItem));
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());
        when(eloStatsRepository.findById("fe-react")).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        // Only 2 saves: one recordReward per profile (no pairwise ELO matches)
        verify(eloStatsRepository, times(2)).save(statsCaptor.capture());

        // Both profiles should still have default ELO (no match happened)
        for (WorkerEloStats stats : statsCaptor.getAllValues()) {
            assertThat(stats.getEloRating()).isEqualTo(1600.0);
            assertThat(stats.getMatchCount()).isZero();
        }
    }

    // ── No DONE items → no updates at all ─────────────────────────────────────

    @Test
    void updateRatings_noDoneItems_noUpdates() {
        UUID planId = UUID.randomUUID();

        PlanItem waitingItem = newItem("BE-001", WorkerType.BE, "be-java");
        // Status is WAITING (never transitioned to DONE)

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(waitingItem));

        service.updateRatingsForPlan(planId);

        verify(eloStatsRepository, never()).save(any());
        verify(eloStatsRepository, never()).findById(any());
    }

    // ── Items missing workerProfile → skipped ─────────────────────────────────

    @Test
    void updateRatings_itemsMissingProfile_skipped() {
        UUID planId = UUID.randomUUID();

        PlanItem noProfile = doneItem("BE-001", WorkerType.BE, null, 0.5f);
        PlanItem withProfile = doneItem("BE-002", WorkerType.BE, "be-java", 0.8f);

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(noProfile, withProfile));
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        // Only be-java should be recorded (1 save for recordReward, no pairwise match)
        verify(eloStatsRepository, times(1)).save(statsCaptor.capture());
        assertThat(statsCaptor.getValue().getWorkerProfile()).isEqualTo("be-java");
    }

    // ── New profiles not in DB → created with default 1600 ELO ────────────────

    @Test
    void updateRatings_newProfiles_createdWithDefault1600() {
        UUID planId = UUID.randomUUID();

        PlanItem item = doneItem("BE-001", WorkerType.BE, "be-rust", 0.6f);

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(eloStatsRepository.findById("be-rust")).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        verify(eloStatsRepository).save(statsCaptor.capture());
        WorkerEloStats created = statsCaptor.getValue();
        assertThat(created.getWorkerProfile()).isEqualTo("be-rust");
        assertThat(created.getEloRating()).isEqualTo(1600.0);
        assertThat(created.getCumulativeReward()).isEqualTo(0.6, within(0.001));
    }

    // ── Multiple workerTypes with pairs → each type updated independently ─────

    @Test
    void updateRatings_multipleWorkerTypesWithPairs_eachTypeIndependent() {
        UUID planId = UUID.randomUUID();

        // BE group: java vs go
        PlanItem beJava = doneItem("BE-001", WorkerType.BE, "be-java", 0.9f);
        PlanItem beGo   = doneItem("BE-002", WorkerType.BE, "be-go", 0.4f);
        // FE group: react vs vue
        PlanItem feReact = doneItem("FE-001", WorkerType.FE, "fe-react", 0.7f);
        PlanItem feVue   = doneItem("FE-002", WorkerType.FE, "fe-vue", 0.8f);

        when(planItemRepository.findByPlanId(planId))
                .thenReturn(List.of(beJava, beGo, feReact, feVue));

        // All profiles new
        when(eloStatsRepository.findById(anyString())).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        // 4 recordReward saves + 2 pairs x 2 profiles = 8 total saves
        verify(eloStatsRepository, atLeast(8)).save(statsCaptor.capture());

        List<WorkerEloStats> allSaved = statsCaptor.getAllValues();

        // BE: java won (0.9 > 0.4)
        WorkerEloStats lastBeJava = findLastSavedFor(allSaved, "be-java");
        WorkerEloStats lastBeGo   = findLastSavedFor(allSaved, "be-go");
        assertThat(lastBeJava.getEloRating()).isGreaterThan(1600.0);
        assertThat(lastBeGo.getEloRating()).isLessThan(1600.0);

        // FE: vue won (0.8 > 0.7)
        WorkerEloStats lastFeReact = findLastSavedFor(allSaved, "fe-react");
        WorkerEloStats lastFeVue   = findLastSavedFor(allSaved, "fe-vue");
        assertThat(lastFeVue.getEloRating()).isGreaterThan(1600.0);
        assertThat(lastFeReact.getEloRating()).isLessThan(1600.0);
    }

    // ── Existing profiles with non-default ELO → ELO updated from existing ───

    @Test
    void updateRatings_existingProfilesWithCustomElo_updatedFromExisting() {
        UUID planId = UUID.randomUUID();

        PlanItem javaItem = doneItem("BE-001", WorkerType.BE, "be-java", 0.8f);
        PlanItem goItem   = doneItem("BE-002", WorkerType.BE, "be-go", 0.3f);

        WorkerEloStats javaStats = new WorkerEloStats("be-java");
        // Simulate an already-elevated ELO
        javaStats.applyEloUpdate(1600.0, true); // won → ELO > 1600

        WorkerEloStats goStats = new WorkerEloStats("be-go");

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(javaItem, goItem));
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.of(javaStats));
        when(eloStatsRepository.findById("be-go")).thenReturn(Optional.of(goStats));
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        double javaEloBefore = javaStats.getEloRating();

        service.updateRatingsForPlan(planId);

        verify(eloStatsRepository, atLeast(4)).save(statsCaptor.capture());

        WorkerEloStats lastJava = findLastSavedFor(statsCaptor.getAllValues(), "be-java");
        // java wins again → ELO increases further above its already-elevated value
        assertThat(lastJava.getEloRating()).isGreaterThan(javaEloBefore);
    }

    // ── Items with null aggregatedReward → skipped ────────────────────────────

    @Test
    void updateRatings_itemsWithNullReward_skipped() {
        UUID planId = UUID.randomUUID();

        PlanItem withReward = doneItem("BE-001", WorkerType.BE, "be-java", 0.5f);
        PlanItem nullReward = doneItemNullReward("BE-002", WorkerType.BE, "be-go");

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(withReward, nullReward));
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        // Only be-java eligible (be-go has null reward)
        verify(eloStatsRepository, times(1)).save(statsCaptor.capture());
        assertThat(statsCaptor.getValue().getWorkerProfile()).isEqualTo("be-java");
    }

    // ── Reward is recorded cumulatively ───────────────────────────────────────

    @Test
    void updateRatings_recordsRewardCumulatively() {
        UUID planId = UUID.randomUUID();

        PlanItem item = doneItem("BE-001", WorkerType.BE, "be-java", 0.75f);

        WorkerEloStats existing = new WorkerEloStats("be-java");
        existing.recordReward(1.0); // already has some cumulative reward

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(item));
        when(eloStatsRepository.findById("be-java")).thenReturn(Optional.of(existing));
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        verify(eloStatsRepository).save(statsCaptor.capture());
        // 1.0 (existing) + 0.75 (new) = 1.75
        assertThat(statsCaptor.getValue().getCumulativeReward()).isEqualTo(1.75, within(0.001));
    }

    // ── Equal rewards → both lose fractional ELO (draw is treated as loss) ────

    @Test
    void updateRatings_equalRewards_bothTreatedAsLoss() {
        UUID planId = UUID.randomUUID();

        PlanItem itemA = doneItem("BE-001", WorkerType.BE, "be-java", 0.5f);
        PlanItem itemB = doneItem("BE-002", WorkerType.BE, "be-go", 0.5f);

        when(planItemRepository.findByPlanId(planId)).thenReturn(List.of(itemA, itemB));
        when(eloStatsRepository.findById(anyString())).thenReturn(Optional.empty());
        when(eloStatsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateRatingsForPlan(planId);

        verify(eloStatsRepository, atLeast(4)).save(statsCaptor.capture());

        List<WorkerEloStats> allSaved = statsCaptor.getAllValues();
        WorkerEloStats lastJava = findLastSavedFor(allSaved, "be-java");
        WorkerEloStats lastGo   = findLastSavedFor(allSaved, "be-go");

        // Equal reward → neither wins → both get actual=0.0, expected=0.5 → both lose 16 ELO points
        assertThat(lastJava.getEloRating()).isLessThan(1600.0);
        assertThat(lastGo.getEloRating()).isLessThan(1600.0);
        // Symmetric: both should lose the same amount
        assertThat(lastJava.getEloRating()).isEqualTo(lastGo.getEloRating(), within(0.001));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static PlanItem doneItem(String taskKey, WorkerType workerType,
                                      String workerProfile, float reward) {
        PlanItem item = newItem(taskKey, workerType, workerProfile);
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);
        item.setAggregatedReward(reward);
        return item;
    }

    private static PlanItem doneItemNullReward(String taskKey, WorkerType workerType,
                                                String workerProfile) {
        PlanItem item = newItem(taskKey, workerType, workerProfile);
        item.transitionTo(ItemStatus.DISPATCHED);
        item.transitionTo(ItemStatus.DONE);
        // aggregatedReward left null
        return item;
    }

    private static PlanItem newItem(String taskKey, WorkerType workerType, String workerProfile) {
        return new PlanItem(UUID.randomUUID(), 1, taskKey, "Title " + taskKey,
                "Desc " + taskKey, workerType, workerProfile, List.of(), List.of());
    }

    /**
     * Finds the last saved WorkerEloStats instance for a given profile.
     * The last save reflects the final state after all updates (recordReward + ELO match).
     */
    private static WorkerEloStats findLastSavedFor(List<WorkerEloStats> allSaved, String profile) {
        WorkerEloStats result = null;
        for (WorkerEloStats stats : allSaved) {
            if (profile.equals(stats.getWorkerProfile())) {
                result = stats;
            }
        }
        assertThat(result)
                .as("Expected at least one save for profile '%s'", profile)
                .isNotNull();
        return result;
    }
}
