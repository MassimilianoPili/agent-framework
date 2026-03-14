package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry.ProfileEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WorkerProfileRegistry} — validation, topic resolution,
 * subscription resolution, and default profile fallback.
 */
class WorkerProfileRegistryTest {

    // ── Validation: valid configurations ──

    @Test
    void validate_validConfig_noErrors() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of(
                        "be-java", entry("BE", "agent-tasks", "be-java-worker-sub"),
                        "fe-react", entry("FE", "agent-tasks", "fe-react-worker-sub")
                ),
                Map.of("BE", "be-java", "FE", "fe-react")
        );

        assertThatCode(registry::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_emptyRegistry_noErrors() {
        WorkerProfileRegistry registry = buildRegistry(Map.of(), Map.of());
        assertThatCode(registry::validate).doesNotThrowAnyException();
    }

    @Test
    void validate_nullDefaults_noErrors() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of("be-java", entry("BE", "agent-tasks", "be-java-worker-sub")),
                Map.of()
        );

        assertThatCode(registry::validate).doesNotThrowAnyException();
    }

    // ── Validation: invalid configurations ──

    @Test
    void validate_emptyTopic_fails() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of("be-java", entry("BE", "", "be-java-worker-sub")),
                Map.of()
        );

        assertThatThrownBy(registry::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty topic");
    }

    @Test
    void validate_emptySubscription_fails() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of("be-java", entry("BE", "agent-tasks", "")),
                Map.of()
        );

        assertThatThrownBy(registry::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty subscription");
    }

    @Test
    void validate_defaultReferencesUnknownProfile_fails() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of("be-java", entry("BE", "agent-tasks", "be-java-worker-sub")),
                Map.of("BE", "be-go")  // be-go doesn't exist
        );

        assertThatThrownBy(registry::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown profile 'be-go'");
    }

    @Test
    void validate_duplicateSubscription_fails() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of(
                        "be-java", entry("BE", "agent-tasks", "shared-sub"),
                        "be-go", entry("BE", "agent-tasks", "shared-sub")  // duplicate
                ),
                Map.of()
        );

        assertThatThrownBy(registry::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate subscription 'shared-sub'");
    }

    @Test
    void validate_multipleErrors_allReported() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of(
                        "bad1", entry("BE", "", ""),    // empty topic + subscription
                        "bad2", entry("FE", "", "sub2") // empty topic
                ),
                Map.of("BE", "ghost")  // unknown default
        );

        assertThatThrownBy(registry::validate)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).contains("empty topic");
                    assertThat(msg).contains("empty subscription");
                    assertThat(msg).contains("unknown profile 'ghost'");
                });
    }

    // ── Topic resolution ──

    @Test
    void resolveTopic_explicitProfile() {
        WorkerProfileRegistry registry = validRegistry();

        assertThat(registry.resolveTopic(WorkerType.BE, "be-java")).isEqualTo("agent-tasks");
    }

    @Test
    void resolveTopic_nullProfile_usesDefault() {
        WorkerProfileRegistry registry = validRegistry();

        assertThat(registry.resolveTopic(WorkerType.BE, null)).isEqualTo("agent-tasks");
    }

    @Test
    void resolveTopic_typeWithoutProfile_usesTopicName() {
        WorkerProfileRegistry registry = validRegistry();

        // AI_TASK has no default → falls back to WorkerType.topicName()
        assertThat(registry.resolveTopic(WorkerType.AI_TASK, null)).isEqualTo("agent-tasks");
    }

    @Test
    void resolveTopic_unknownProfile_throws() {
        WorkerProfileRegistry registry = validRegistry();

        assertThatThrownBy(() -> registry.resolveTopic(WorkerType.BE, "be-cobol"))
                .isInstanceOf(WorkerProfileRegistry.UnknownWorkerProfileException.class)
                .hasMessageContaining("be-cobol");
    }

    // ── Subscription resolution ──

    @Test
    void resolveSubscription_explicitProfile() {
        WorkerProfileRegistry registry = validRegistry();

        assertThat(registry.resolveSubscription(WorkerType.BE, "be-java"))
                .isEqualTo("be-java-worker-sub");
    }

    @Test
    void resolveSubscription_nullProfile_usesDefault() {
        WorkerProfileRegistry registry = validRegistry();

        assertThat(registry.resolveSubscription(WorkerType.FE, null))
                .isEqualTo("fe-react-worker-sub");
    }

    // ── Default profile resolution ──

    @Test
    void resolveDefaultProfile_knownType() {
        WorkerProfileRegistry registry = validRegistry();

        assertThat(registry.resolveDefaultProfile(WorkerType.BE)).isEqualTo("be-java");
        assertThat(registry.resolveDefaultProfile(WorkerType.FE)).isEqualTo("fe-react");
    }

    @Test
    void resolveDefaultProfile_typeWithoutDefault_returnsNull() {
        WorkerProfileRegistry registry = validRegistry();

        assertThat(registry.resolveDefaultProfile(WorkerType.AI_TASK)).isNull();
    }

    // ── profilesForWorkerType ──

    @Test
    void profilesForWorkerType_multipleProfiles() {
        WorkerProfileRegistry registry = buildRegistry(
                Map.of(
                        "be-java", entry("BE", "agent-tasks", "be-java-sub"),
                        "be-go", entry("BE", "agent-tasks", "be-go-sub"),
                        "fe-react", entry("FE", "agent-tasks", "fe-react-sub")
                ),
                Map.of("BE", "be-java", "FE", "fe-react")
        );

        List<String> beProfiles = registry.profilesForWorkerType(WorkerType.BE);
        assertThat(beProfiles).containsExactlyInAnyOrder("be-java", "be-go");
    }

    @Test
    void profilesForWorkerType_typeWithNoProfiles_returnsEmpty() {
        WorkerProfileRegistry registry = validRegistry();
        assertThat(registry.profilesForWorkerType(WorkerType.AI_TASK)).isEmpty();
    }

    // ── Profile entry lookup ──

    @Test
    void getProfileEntry_existingProfile() {
        WorkerProfileRegistry registry = validRegistry();

        ProfileEntry entry = registry.getProfileEntry("be-java");
        assertThat(entry).isNotNull();
        assertThat(entry.getWorkerType()).isEqualTo("BE");
        assertThat(entry.getTopic()).isEqualTo("agent-tasks");
        assertThat(entry.getSubscription()).isEqualTo("be-java-worker-sub");
    }

    @Test
    void getProfileEntry_unknownProfile_returnsNull() {
        WorkerProfileRegistry registry = validRegistry();
        assertThat(registry.getProfileEntry("be-cobol")).isNull();
    }

    // ── Topic-per-type splitting (#21) ──

    @Test
    void resolveTopic_topicPerTypeEnabled_appendsWorkerType() {
        WorkerProfileRegistry registry = validRegistry();
        registry.setTopicPerType(true);

        assertThat(registry.resolveTopic(WorkerType.BE, "be-java")).isEqualTo("agent-tasks:BE");
        assertThat(registry.resolveTopic(WorkerType.FE, "fe-react")).isEqualTo("agent-tasks:FE");
    }

    @Test
    void resolveTopic_topicPerTypeEnabled_nullProfile_appendsWorkerType() {
        WorkerProfileRegistry registry = validRegistry();
        registry.setTopicPerType(true);

        assertThat(registry.resolveTopic(WorkerType.BE, null)).isEqualTo("agent-tasks:BE");
    }

    @Test
    void resolveTopic_topicPerTypeEnabled_typeWithoutProfile_appendsWorkerType() {
        WorkerProfileRegistry registry = validRegistry();
        registry.setTopicPerType(true);

        assertThat(registry.resolveTopic(WorkerType.AI_TASK, null)).isEqualTo("agent-tasks:AI_TASK");
    }

    @Test
    void resolveTopic_topicPerTypeEnabled_dedicatedTopic_notSplit() {
        WorkerProfileRegistry registry = validRegistry();
        registry.setTopicPerType(true);

        // REVIEW has its own topic "agent-reviews" — should NOT be split
        assertThat(registry.resolveTopic(WorkerType.REVIEW, null)).isEqualTo("agent-reviews");
    }

    @Test
    void resolveTopic_topicPerTypeDisabled_noSplit() {
        WorkerProfileRegistry registry = validRegistry();
        registry.setTopicPerType(false);

        assertThat(registry.resolveTopic(WorkerType.BE, "be-java")).isEqualTo("agent-tasks");
        assertThat(registry.resolveTopic(WorkerType.FE, "fe-react")).isEqualTo("agent-tasks");
    }

    // ── Helpers ──

    private WorkerProfileRegistry validRegistry() {
        return buildRegistry(
                Map.of(
                        "be-java", entry("BE", "agent-tasks", "be-java-worker-sub"),
                        "fe-react", entry("FE", "agent-tasks", "fe-react-worker-sub")
                ),
                Map.of("BE", "be-java", "FE", "fe-react")
        );
    }

    private WorkerProfileRegistry buildRegistry(Map<String, ProfileEntry> profiles,
                                                 Map<String, String> defaults) {
        WorkerProfileRegistry registry = new WorkerProfileRegistry();
        registry.setProfiles(profiles);
        registry.setDefaults(defaults);
        return registry;
    }

    private ProfileEntry entry(String workerType, String topic, String subscription) {
        return new ProfileEntry(workerType, topic, subscription, workerType + " Worker",
                List.of("git"), List.of("src/"));
    }
}
