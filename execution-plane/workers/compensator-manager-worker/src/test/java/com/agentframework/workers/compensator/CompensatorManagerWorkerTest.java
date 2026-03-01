package com.agentframework.workers.compensator;

import com.agentframework.worker.ToolAllowlist;
import com.agentframework.worker.WorkerExecutionException;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompensatorManagerWorkerTest {

    private CompensatorManagerWorker worker;
    private ChatClient mockChatClient;

    @BeforeEach
    void setUp() {
        worker = new CompensatorManagerWorker(
                mock(AgentContextBuilder.class),
                mock(WorkerChatClientFactory.class),
                mock(WorkerResultProducer.class),
                List.of()
        );
        mockChatClient = mock(ChatClient.class);
    }

    @Test
    void workerType_returnsCompensatorManager() {
        assertThat(worker.workerType()).isEqualTo("COMPENSATOR_MANAGER");
    }

    @Test
    void toolAllowlist_containsGitAndFilesystemTools() {
        ToolAllowlist allowlist = worker.toolAllowlist();

        assertThat(allowlist).isInstanceOf(ToolAllowlist.Explicit.class);
        List<String> tools = ((ToolAllowlist.Explicit) allowlist).tools();
        assertThat(tools).containsExactly(
                "git_status", "git_log", "git_diff", "git_revert",
                "git_checkout", "git_stash", "read_file", "write_file", "list_directory");
    }

    @Test
    void skillPaths_containsGitOperations() {
        assertThat(worker.skillPaths()).containsExactly("skills/git-operations");
    }

    @Test
    void execute_happyPath_returnsChatResponseText() throws Exception {
        String expectedJson = """
                {"compensated": true, "commits_reverted": ["abc123"], "compensation_summary": "Reverted BE-001"}""";
        setupChatClientMock(expectedJson, mockUsage());

        AgentContext context = compensationContext();
        String result = worker.execute(context, mockChatClient);

        assertThat(result).isEqualTo(expectedJson);
    }

    @Test
    void execute_chatClientThrows_wrapsInWorkerExecutionException() {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(mockChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM timeout"));

        AgentContext context = compensationContext();

        assertThatThrownBy(() -> worker.execute(context, mockChatClient))
                .isInstanceOf(WorkerExecutionException.class)
                .hasMessageContaining("COMPENSATOR_MANAGER worker execution failed")
                .hasMessageContaining("COMP-BE-001")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void execute_nullMetadata_doesNotThrow() throws Exception {
        String expectedJson = """
                {"compensated": false, "reason": "No commits found"}""";
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = new AssistantMessage(expectedJson);

        when(mockChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(chatResponse.getMetadata()).thenReturn(null);

        String result = worker.execute(compensationContext(), mockChatClient);

        assertThat(result).isEqualTo(expectedJson);
    }

    @Test
    void execute_nullUsage_doesNotThrow() throws Exception {
        String expectedJson = """
                {"compensated": true, "commits_reverted": ["def456"]}""";
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = new AssistantMessage(expectedJson);

        when(mockChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(null);

        String result = worker.execute(compensationContext(), mockChatClient);

        assertThat(result).isEqualTo(expectedJson);
    }

    @Test
    void execute_includesCompensationContextInPrompt() throws Exception {
        setupChatClientMock("{\"compensated\": true}", mockUsage());

        AgentContext context = compensationContext();
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);

        // Re-setup with captor
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = mockChatResponse("{\"compensated\": true}", mockUsage());

        when(mockChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(userPromptCaptor.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);

        worker.execute(context, mockChatClient);

        String capturedPrompt = userPromptCaptor.getValue();
        assertThat(capturedPrompt).contains("COMP-BE-001");
        assertThat(capturedPrompt).contains("Compensate: Build user API");
        assertThat(capturedPrompt).contains("original_task");
        assertThat(capturedPrompt).contains("compensation_reason");
        assertThat(capturedPrompt).contains("Compensator Manager Worker");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static AgentContext compensationContext() {
        String compensationDescription = """
                {
                  "original_task": {"key": "BE-001", "title": "Build user API", "description": "REST API"},
                  "original_result": {"code": "ok", "files_modified": ["UserController.java"]},
                  "compensation_reason": "Wrong approach, need to revert"
                }
                """;
        return new AgentContext(
                "COMP-BE-001", "Compensate: Build user API", compensationDescription,
                null, "You are a compensator agent.",
                Map.of(), null, null, null, null
        );
    }

    private Usage mockUsage() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(500);
        when(usage.getCompletionTokens()).thenReturn(200);
        when(usage.getTotalTokens()).thenReturn(700);
        return usage;
    }

    private ChatResponse mockChatResponse(String text, Usage usage) {
        ChatResponse chatResponse = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = new AssistantMessage(text);

        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(metadata.getUsage()).thenReturn(usage);
        return chatResponse;
    }

    private void setupChatClientMock(String responseText, Usage usage) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = mockChatResponse(responseText, usage);

        when(mockChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
    }
}
