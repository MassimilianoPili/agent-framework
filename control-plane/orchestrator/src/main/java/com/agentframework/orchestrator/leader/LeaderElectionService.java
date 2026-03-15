package com.agentframework.orchestrator.leader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Distributed leader election for multi-instance orchestrator deployments.
 *
 * <p>Uses Redis SET NX (set-if-not-exists) with a TTL to guarantee that exactly one
 * orchestrator instance is active at any time. The active instance holds the lock
 * {@code orchestrator:leader → instanceId} and refreshes it periodically.</p>
 *
 * <p>When leadership changes:</p>
 * <ul>
 *   <li>{@link LeaderAcquiredEvent} — this instance just became leader; consumers should start.</li>
 *   <li>{@link LeaderLostEvent} — this instance lost leadership; consumers should stop.</li>
 * </ul>
 *
 * <p>Failover latency ≈ TTL (default 30 s). Refresh interval must be &lt; TTL / 3
 * to avoid accidental expiry under load (default: 10 s &lt; 30 s / 3 = 10 s — boundary;
 * use 8 s in practice for safety margin).</p>
 */
@Service
@ConditionalOnProperty(prefix = "orchestrator.leader-election",
        name = "enabled", havingValue = "true", matchIfMissing = true)
public class LeaderElectionService {

    private static final Logger log = LoggerFactory.getLogger(LeaderElectionService.class);
    private static final String LEADER_KEY = "orchestrator:leader";

    @Value("${orchestrator.leader-election.ttl-ms:30000}")
    private long ttlMs;

    private final StringRedisTemplate redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    /** Unique identifier for this JVM instance — survives heartbeat cycles. */
    private final String instanceId = UUID.randomUUID().toString();

    /** Volatile: read by other threads (e.g. OrchestrationService dispatch thread). */
    private volatile boolean leader = false;

    public LeaderElectionService(@Qualifier("redisMessagingTemplate") StringRedisTemplate redisTemplate,
                                  ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Attempts to acquire or renew the leader lock. Called periodically by the scheduler.
     *
     * <p>Three paths:</p>
     * <ol>
     *   <li>SET NX succeeds → we just became leader.</li>
     *   <li>SET NX fails, but lock belongs to us → renew TTL (we are still leader).</li>
     *   <li>Lock belongs to another instance → we are not leader.</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${orchestrator.leader-election.refresh-ms:10000}")
    public void heartbeat() {
        try {
            boolean acquired = Boolean.TRUE.equals(
                    redisTemplate.opsForValue()
                                 .setIfAbsent(LEADER_KEY, instanceId, Duration.ofMillis(ttlMs)));

            if (acquired) {
                promoteToLeader();
                return;
            }

            // Lock already held — check if it's ours
            String currentHolder = redisTemplate.opsForValue().get(LEADER_KEY);
            if (instanceId.equals(currentHolder)) {
                // Renew TTL
                redisTemplate.expire(LEADER_KEY, Duration.ofMillis(ttlMs));
                if (!leader) {
                    // Edge case: we hold the lock but leader flag was false (restart scenario)
                    promoteToLeader();
                }
            } else {
                // Another instance holds the lock
                if (leader) {
                    leader = false;
                    log.warn("Lost leader role to instance {} — entering standby", currentHolder);
                    eventPublisher.publishEvent(new LeaderLostEvent(instanceId));
                }
            }
        } catch (Exception e) {
            // Redis unavailable: conservatively demote (safer than dual-active)
            if (leader) {
                leader = false;
                log.error("Redis error during leader heartbeat — demoting to standby: {}", e.getMessage());
                eventPublisher.publishEvent(new LeaderLostEvent(instanceId));
            }
        }
    }

    private void promoteToLeader() {
        if (!leader) {
            leader = true;
            log.info("Acquired leader lock (instanceId={})", instanceId);
            eventPublisher.publishEvent(new LeaderAcquiredEvent(instanceId));
        }
    }

    /** Returns true if this instance is currently the active leader. */
    public boolean isLeader() {
        return leader;
    }

    /** Returns the unique identifier for this JVM instance. */
    public String getInstanceId() {
        return instanceId;
    }
}
