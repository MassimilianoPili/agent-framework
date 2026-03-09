package com.agentframework.orchestrator.eventsourcing;

import com.agentframework.common.util.HashUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for hash chain computation in {@link PlanEventStore#append} (#30).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanEventStore — Hash Chain (#30)")
class PlanEventStoreHashTest {

    @Mock private PlanEventRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PlanEventStore store;

    @BeforeEach
    void setUp() {
        store = new PlanEventStore(repository, objectMapper);
    }

    @Test
    @DisplayName("First event uses genesis hash as previousHash")
    void firstEvent_usesGenesisHash() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.findTopByPlanIdOrderBySequenceNumberDesc(planId))
                .thenReturn(Optional.empty());
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanEvent event = store.append(planId, null, "PLAN_STARTED", null);

        assertThat(event.getPreviousHash()).isEqualTo(PlanEventStore.GENESIS_HASH);
        assertThat(event.getEventHash()).hasSize(64);
        assertThat(event.getEventHash()).isNotEqualTo(PlanEventStore.GENESIS_HASH);
    }

    @Test
    @DisplayName("Second event chains to first event's hash")
    void secondEvent_chainsToPrevious() {
        UUID planId = UUID.randomUUID();

        // First event
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.findTopByPlanIdOrderBySequenceNumberDesc(planId))
                .thenReturn(Optional.empty());
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        PlanEvent first = store.append(planId, null, "PLAN_STARTED", null);

        // Second event — previous is the first event
        when(repository.nextSequence(planId)).thenReturn(2L);
        when(repository.findTopByPlanIdOrderBySequenceNumberDesc(planId))
                .thenReturn(Optional.of(first));
        PlanEvent second = store.append(planId, null, "TASK_DISPATCHED", Map.of("taskKey", "BE-001"));

        assertThat(second.getPreviousHash()).isEqualTo(first.getEventHash());
        assertThat(second.getEventHash()).hasSize(64);
        assertThat(second.getEventHash()).isNotEqualTo(first.getEventHash());
    }

    @Test
    @DisplayName("Hash is deterministic for the same inputs")
    void hashIsDeterministic() {
        String previousHash = PlanEventStore.GENESIS_HASH;
        String eventType = "PLAN_STARTED";
        String payload = "{}";
        String occurredAt = "2026-03-09T15:00:00Z";

        String hash1 = HashUtil.sha256(previousHash + "|" + eventType + "|" + payload + "|" + occurredAt);
        String hash2 = HashUtil.sha256(previousHash + "|" + eventType + "|" + payload + "|" + occurredAt);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64);
    }

    @Test
    @DisplayName("Different payloads produce different hashes")
    void hashChangesWithDifferentPayload() {
        String previousHash = PlanEventStore.GENESIS_HASH;
        String eventType = "TASK_COMPLETED";
        String occurredAt = "2026-03-09T15:00:00Z";

        String hash1 = HashUtil.sha256(previousHash + "|" + eventType + "|" + "{\"status\":\"DONE\"}" + "|" + occurredAt);
        String hash2 = HashUtil.sha256(previousHash + "|" + eventType + "|" + "{\"status\":\"FAILED\"}" + "|" + occurredAt);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("Event hash and previousHash are persisted via save()")
    void hashFieldsArePersisted() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.findTopByPlanIdOrderBySequenceNumberDesc(planId))
                .thenReturn(Optional.empty());
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanEvent event = store.append(planId, null, "PLAN_STARTED", null);

        // Both hash fields must be non-empty
        assertThat(event.getEventHash()).isNotEmpty();
        assertThat(event.getPreviousHash()).isNotEmpty();
        // previousHash is genesis for first event
        assertThat(event.getPreviousHash()).matches("^0{64}$");
        // eventHash is a valid SHA-256 hex
        assertThat(event.getEventHash()).matches("^[0-9a-f]{64}$");
    }
}
