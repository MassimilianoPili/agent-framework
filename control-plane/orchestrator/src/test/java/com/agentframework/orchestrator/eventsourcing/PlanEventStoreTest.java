package com.agentframework.orchestrator.eventsourcing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlanEventStore} — sequence numbering, payload serialization,
 * and error handling.
 */
@ExtendWith(MockitoExtension.class)
class PlanEventStoreTest {

    @Mock
    private PlanEventRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PlanEventStore store;

    @BeforeEach
    void setUp() {
        store = new PlanEventStore(repository, objectMapper);
    }

    @Test
    void append_usesNextSequenceFromRepository() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(5L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanEvent event = store.append(planId, null, "PLAN_STARTED", null);

        assertThat(event.getSequenceNumber()).isEqualTo(5L);
        verify(repository).nextSequence(planId);
    }

    @Test
    void append_firstEventForPlan_sequenceIsOne() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanEvent event = store.append(planId, null, "PLAN_STARTED", Map.of("status", "RUNNING"));

        assertThat(event.getSequenceNumber()).isEqualTo(1L);
    }

    @Test
    void append_nullPayload_producesEmptyJsonObject() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanEvent event = store.append(planId, null, "PLAN_STARTED", null);

        assertThat(event.getPayload()).isEqualTo("{}");
    }

    @Test
    void append_validPayload_serializesToJson() {
        UUID planId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(2L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> payload = Map.of("status", "DONE", "retryCount", 3);
        PlanEvent event = store.append(planId, itemId, "ITEM_COMPLETED", payload);

        assertThat(event.getPayload()).contains("\"status\":\"DONE\"");
        assertThat(event.getPayload()).contains("\"retryCount\":3");
        assertThat(event.getItemId()).isEqualTo(itemId);
    }

    @Test
    void append_nonSerializablePayload_fallsBackToEmptyJson() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // Self-referencing object that Jackson cannot serialize
        Object badPayload = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() { return this; }
        };

        PlanEvent event = store.append(planId, null, "BAD_EVENT", badPayload);

        assertThat(event.getPayload()).isEqualTo("{}");
    }

    @Test
    void append_setsEventTypeAndTimestamp() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        PlanEvent event = store.append(planId, null, "PLAN_COMPLETED", null);

        assertThat(event.getEventType()).isEqualTo("PLAN_COMPLETED");
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getId()).isNotNull();
    }

    @Test
    void append_savesEventViaRepository() {
        UUID planId = UUID.randomUUID();
        when(repository.nextSequence(planId)).thenReturn(1L);
        when(repository.save(any(PlanEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        store.append(planId, null, "PLAN_STARTED", null);

        ArgumentCaptor<PlanEvent> captor = ArgumentCaptor.forClass(PlanEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPlanId()).isEqualTo(planId);
    }
}
