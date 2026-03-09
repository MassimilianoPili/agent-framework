package com.agentframework.orchestrator.eventsourcing;

import com.agentframework.common.util.HashUtil;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Verifies the integrity of a plan's event hash chain (#30).
 *
 * <p>Performs an O(N) sequential scan of all events for a given plan,
 * recomputing each hash and checking linkage to the previous event.
 * If tampering is detected, logs a {@code SECURITY WARNING} and publishes
 * an {@code INTEGRITY_VIOLATION} domain event for alerting.</p>
 *
 * <p>Graceful degradation: verification failures do not block orchestration.
 * The system continues operating and the anomaly is signalled for external
 * monitoring and audit investigation.</p>
 */
@Service
public class HashChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(HashChainVerifier.class);

    private final PlanEventRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public HashChainVerifier(PlanEventRepository repository,
                             ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Verifies the hash chain for all events in the given plan.
     *
     * @param planId the plan to verify
     * @return verification result with details if the chain is broken
     */
    @Transactional(readOnly = true)
    public HashChainVerificationResult verify(UUID planId) {
        List<PlanEvent> events = repository.findByPlanIdOrderBySequenceNumberAsc(planId);
        if (events.isEmpty()) {
            return HashChainVerificationResult.empty();
        }

        String expectedPreviousHash = PlanEventStore.GENESIS_HASH;
        int verifiedCount = 0;

        for (PlanEvent event : events) {
            // Skip pre-migration events (empty hashes from V27 default)
            if (event.getEventHash().isEmpty()) {
                continue;
            }

            // Check previousHash linkage
            if (!expectedPreviousHash.equals(event.getPreviousHash())) {
                return fail(planId, event, "previousHash mismatch: expected "
                        + expectedPreviousHash.substring(0, 8) + "... got "
                        + event.getPreviousHash().substring(0, Math.min(8, event.getPreviousHash().length())) + "...");
            }

            // Recompute eventHash and verify
            String hashInput = event.getPreviousHash() + "|" + event.getEventType()
                    + "|" + event.getPayload() + "|" + event.getOccurredAt();
            String recomputed = HashUtil.sha256(hashInput);

            if (!recomputed.equals(event.getEventHash())) {
                return fail(planId, event, "eventHash mismatch: recomputed "
                        + recomputed.substring(0, 8) + "... stored "
                        + event.getEventHash().substring(0, Math.min(8, event.getEventHash().length())) + "...");
            }

            expectedPreviousHash = event.getEventHash();
            verifiedCount++;
        }

        log.debug("Hash chain verified: plan={}, events={} (verified={})",
                planId, events.size(), verifiedCount);
        return HashChainVerificationResult.valid(verifiedCount);
    }

    private HashChainVerificationResult fail(UUID planId, PlanEvent event, String reason) {
        log.error("SECURITY WARNING: hash chain integrity violation — plan={} seq={}: {}",
                planId, event.getSequenceNumber(), reason);

        eventPublisher.publishEvent(SpringPlanEvent.forSystem("INTEGRITY_VIOLATION",
                String.format("{\"planId\":\"%s\",\"sequence\":%d,\"reason\":\"%s\"}",
                        planId, event.getSequenceNumber(), reason.replace("\"", "\\\""))));

        return HashChainVerificationResult.broken(event.getSequenceNumber(), reason);
    }
}
