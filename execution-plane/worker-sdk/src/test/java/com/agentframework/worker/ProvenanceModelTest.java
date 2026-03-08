package com.agentframework.worker;

import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.dto.AgentResult;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.messaging.WorkerResultProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that AbstractWorker correctly populates Provenance.model (#20 completion).
 *
 * <ul>
 *   <li>When task.modelId() is set → Provenance.model records that override.</li>
 *   <li>When task.modelId() is null → Provenance.model falls back to the @Value default.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProvenanceModelTest {

    @Mock private AgentContextBuilder contextBuilder;
    @Mock private WorkerChatClientFactory chatClientFactory;
    @Mock private WorkerResultProducer resultProducer;
    @Mock private ChatClient chatClient;

    private AbstractWorker worker;

    @BeforeEach
    void setUp() {
        // Concrete inline subclass — the simplest execute() that returns valid JSON
        worker = new AbstractWorker(contextBuilder, chatClientFactory, resultProducer) {
            @Override public String workerType() { return "BE"; }
            @Override protected String systemPromptFile() { return "skills/test.md"; }
            @Override protected String execute(AgentContext context, ChatClient client) {
                return "{\"result\":\"ok\"}";
            }
        };

        // Inject @Value field without a Spring context
        ReflectionTestUtils.setField(worker, "defaultModelId", "claude-sonnet-4-6");

        // Stub context builder
        AgentContext stubContext = new AgentContext(
            UUID.randomUUID(), UUID.randomUUID(), "T-001", "title", "desc",
            "spec", "system prompt", Map.of(), "skills", List.of(), null, null, null);
        when(contextBuilder.build(any(AgentTask.class), any())).thenReturn(stubContext);
        when(chatClientFactory.create(any(), any(), any())).thenReturn(chatClient);
    }

    @Test
    void provenance_withModelOverride_recordsActualModelId() {
        AgentTask task = taskWithModelId("claude-haiku-4-5-20251001");

        worker.process(task);

        ArgumentCaptor<AgentResult> captor = ArgumentCaptor.forClass(AgentResult.class);
        verify(resultProducer).publish(captor.capture());
        AgentResult result = captor.getValue();

        assertThat(result.provenance()).isNotNull();
        assertThat(result.provenance().model()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(result.modelId()).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void provenance_withNullModelId_recordsDefaultModel() {
        AgentTask task = taskWithModelId(null);

        worker.process(task);

        ArgumentCaptor<AgentResult> captor = ArgumentCaptor.forClass(AgentResult.class);
        verify(resultProducer).publish(captor.capture());
        AgentResult result = captor.getValue();

        assertThat(result.provenance()).isNotNull();
        assertThat(result.provenance().model()).isEqualTo("claude-sonnet-4-6");
        assertThat(result.modelId()).isEqualTo("claude-sonnet-4-6");
    }

    // ────────────────────────────────────────────────────────────────────────

    private static AgentTask taskWithModelId(String modelId) {
        return new AgentTask(
            UUID.randomUUID(), UUID.randomUUID(),
            "T-001", "title", "description", "BE", "be-java",
            "spec", null, 1, UUID.randomUUID(), UUID.randomUUID(),
            "2026-01-01T00:00:00Z", null, null, null, null, null,
            modelId
        );
    }
}
