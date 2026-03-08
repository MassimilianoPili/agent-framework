package com.agentframework.orchestrator.leader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LeaderElectionServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ApplicationEventPublisher eventPublisher;

    private LeaderElectionService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new LeaderElectionService(redisTemplate, eventPublisher);
        ReflectionTestUtils.setField(service, "ttlMs", 30000L);
    }

    @Test
    void heartbeat_firstInstance_becomesLeader() {
        // SET NX succeeds → lock acquired
        when(valueOps.setIfAbsent(eq("orchestrator:leader"), anyString(), any(Duration.class)))
                .thenReturn(true);

        service.heartbeat();

        assertThat(service.isLeader()).isTrue();

        ArgumentCaptor<LeaderAcquiredEvent> captor = ArgumentCaptor.forClass(LeaderAcquiredEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().instanceId()).isEqualTo(service.getInstanceId());
    }

    @Test
    void heartbeat_whenAlreadyOwner_renewsTtl() {
        // SET NX fails, but GET returns our own instanceId → renew TTL
        when(valueOps.setIfAbsent(eq("orchestrator:leader"), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get("orchestrator:leader")).thenReturn(service.getInstanceId());

        service.heartbeat();

        // Should have renewed the TTL
        verify(redisTemplate).expire(eq("orchestrator:leader"), any(Duration.class));
        // No event published (we were already leader on first call or not yet leader)
        // After this single call without prior acquisition, leader remains false — just TTL renewed
    }

    @Test
    void heartbeat_whenOtherOwner_remainsFollower() {
        // SET NX fails, GET returns a foreign instanceId → stay as follower
        when(valueOps.setIfAbsent(eq("orchestrator:leader"), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOps.get("orchestrator:leader")).thenReturn("some-other-instance-id");

        service.heartbeat();

        assertThat(service.isLeader()).isFalse();
        // No LeaderLostEvent because we were never leader
        verify(eventPublisher, never()).publishEvent(any(LeaderLostEvent.class));
    }
}
