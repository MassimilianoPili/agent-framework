package com.agentframework.orchestrator.eventsourcing;

import com.agentframework.common.util.HashUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HashChainVerifier} — tamper-proof hash chain verification (#30).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HashChainVerifier (#30)")
class HashChainVerifierTest {

    @Mock private PlanEventRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private HashChainVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new HashChainVerifier(repository, eventPublisher);
    }

    @Test
    @DisplayName("Empty plan returns valid result with 0 events")
    void emptyPlan_returnsValid() {
        UUID planId = UUID.randomUUID();
        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of());

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isTrue();
        assertThat(result.eventCount()).isZero();
        assertThat(result.brokenAtSequence()).isNull();
    }

    @Test
    @DisplayName("Single correctly hashed event is valid")
    void singleEvent_valid() {
        UUID planId = UUID.randomUUID();
        PlanEvent event = buildEvent(planId, 1, PlanEventStore.GENESIS_HASH,
                "PLAN_STARTED", "{}", Instant.parse("2026-03-09T15:00:00Z"));

        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(event));

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isTrue();
        assertThat(result.eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Chain of 3 correctly linked events is valid")
    void multipleEvents_validChain() {
        UUID planId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-03-09T15:00:00Z");
        Instant t2 = Instant.parse("2026-03-09T15:00:01Z");
        Instant t3 = Instant.parse("2026-03-09T15:00:02Z");

        PlanEvent e1 = buildEvent(planId, 1, PlanEventStore.GENESIS_HASH,
                "PLAN_STARTED", "{}", t1);
        PlanEvent e2 = buildEvent(planId, 2, e1.getEventHash(),
                "TASK_DISPATCHED", "{\"taskKey\":\"BE-001\"}", t2);
        PlanEvent e3 = buildEvent(planId, 3, e2.getEventHash(),
                "TASK_COMPLETED", "{\"success\":true}", t3);

        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(List.of(e1, e2, e3));

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isTrue();
        assertThat(result.eventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Tampered payload is detected")
    void tamperedPayload_detectsBroken() {
        UUID planId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-03-09T15:00:00Z");
        Instant t2 = Instant.parse("2026-03-09T15:00:01Z");

        PlanEvent e1 = buildEvent(planId, 1, PlanEventStore.GENESIS_HASH,
                "PLAN_STARTED", "{}", t1);

        // Build e2 with correct hash, then simulate tampering the payload
        PlanEvent e2Original = buildEvent(planId, 2, e1.getEventHash(),
                "TASK_DISPATCHED", "{\"taskKey\":\"BE-001\"}", t2);

        // Tampered: different payload but original eventHash
        PlanEvent e2Tampered = new PlanEvent(
                UUID.randomUUID(), planId, null,
                "TASK_DISPATCHED", "{\"taskKey\":\"TAMPERED\"}", t2, 2,
                e2Original.getEventHash(), e2Original.getPreviousHash());

        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(List.of(e1, e2Tampered));

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.reason()).contains("eventHash mismatch");
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Tampered eventHash is detected")
    void tamperedEventHash_detectsBroken() {
        UUID planId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-03-09T15:00:00Z");

        // Build event with wrong eventHash
        String correctPreviousHash = PlanEventStore.GENESIS_HASH;
        PlanEvent e1 = new PlanEvent(
                UUID.randomUUID(), planId, null,
                "PLAN_STARTED", "{}", t1, 1,
                "deadbeef".repeat(8), correctPreviousHash);

        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(e1));

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(1);
        assertThat(result.reason()).contains("eventHash mismatch");
    }

    @Test
    @DisplayName("Broken previousHash linkage is detected")
    void brokenPreviousHashLink_detectsBroken() {
        UUID planId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-03-09T15:00:00Z");
        Instant t2 = Instant.parse("2026-03-09T15:00:01Z");

        PlanEvent e1 = buildEvent(planId, 1, PlanEventStore.GENESIS_HASH,
                "PLAN_STARTED", "{}", t1);

        // e2 has wrong previousHash (doesn't link to e1)
        String wrongPreviousHash = "abcdef01".repeat(8);
        PlanEvent e2 = buildEvent(planId, 2, wrongPreviousHash,
                "TASK_DISPATCHED", "{}", t2);

        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId))
                .thenReturn(List.of(e1, e2));

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenAtSequence()).isEqualTo(2);
        assertThat(result.reason()).contains("previousHash mismatch");
    }

    @Test
    @DisplayName("Pre-migration events with empty hashes are skipped")
    void preMigrationEvents_skipped() {
        UUID planId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-03-09T15:00:00Z");

        // Pre-migration event: empty hashes
        PlanEvent legacy = new PlanEvent(
                UUID.randomUUID(), planId, null,
                "PLAN_STARTED", "{}", t1, 1);
        // eventHash and previousHash default to ""

        when(repository.findByPlanIdOrderBySequenceNumberAsc(planId)).thenReturn(List.of(legacy));

        HashChainVerificationResult result = verifier.verify(planId);

        assertThat(result.valid()).isTrue();
        assertThat(result.eventCount()).isZero(); // no hashed events to verify
    }

    // ── Test helper ──────────────────────────────────────────────────────────

    /**
     * Builds a correctly hashed PlanEvent (hash is computed from inputs).
     */
    private PlanEvent buildEvent(UUID planId, long seq, String previousHash,
                                  String eventType, String payload, Instant occurredAt) {
        String hashInput = previousHash + "|" + eventType + "|" + payload + "|" + occurredAt;
        String eventHash = HashUtil.sha256(hashInput);
        return new PlanEvent(UUID.randomUUID(), planId, null,
                eventType, payload, occurredAt, seq, eventHash, previousHash);
    }
}
