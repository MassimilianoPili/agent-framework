package com.agentframework.worker.claude;

import com.agentframework.worker.config.CompactingToolCallingManagerPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CompactingToolCallingManager} — context window overflow
 * prevention via conversation history compaction.
 *
 * <p>Uses low thresholds (maxTokens=1000, threshold=0.5 → 500 tokens ≈ 1750 chars)
 * to trigger compaction easily in tests without creating huge strings.</p>
 */
@ExtendWith(MockitoExtension.class)
class CompactingToolCallingManagerTest {

    @Mock
    private ToolCallingManager delegate;

    @Mock
    private Prompt prompt;

    @Mock
    private ChatResponse chatResponse;

    /** Low max tokens for easy threshold triggering in tests. */
    private static final int MAX_TOKENS = 1000;

    /** 50% threshold = 500 tokens ≈ 1750 chars (at 3.5 chars/token). */
    private static final double THRESHOLD = 0.5;

    private CompactingToolCallingManager manager;

    @BeforeEach
    void setUp() {
        manager = new CompactingToolCallingManager(delegate, MAX_TOKENS, THRESHOLD);
    }

    // ── Passthrough (below threshold) ───────────────────────────────────────────

    @Test
    void belowThreshold_passthrough() {
        // Short history: ~100 chars total = ~29 tokens << 500 threshold
        List<Message> shortHistory = List.of(
                new SystemMessage("You are a worker."),
                new UserMessage("Build the feature."),
                new AssistantMessage("OK, reading file."),
                toolResponse("call_1", "fs_read", "short content")
        );
        ToolExecutionResult delegateResult = buildResult(shortHistory);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);

        assertThat(result.conversationHistory()).isEqualTo(shortHistory);
    }

    // ── Small history (≤4 messages) ─────────────────────────────────────────────

    @Test
    void smallHistory_noCompaction() {
        // Even with large content, ≤4 messages can't be compacted (nothing in the middle)
        List<Message> smallHistory = List.of(
                new SystemMessage("System"),
                new UserMessage("User"),
                new AssistantMessage("Assistant"),
                toolResponse("call_1", "fs_read", "x".repeat(5000))
        );
        ToolExecutionResult delegateResult = buildResult(smallHistory);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);

        assertThat(result.conversationHistory()).hasSize(4);
        // The tool response should be unchanged (no middle section to compact)
        ToolResponseMessage trm = (ToolResponseMessage) result.conversationHistory().get(3);
        assertThat(trm.getResponses().get(0).responseData()).hasSize(5000);
    }

    // ── Compaction (above threshold) ────────────────────────────────────────────

    @Test
    void aboveThreshold_compactsMiddleToolResponses() {
        // Build history with 8 messages: 2 head + 2 middle + 4 tail
        // Middle tool response has 3000 chars (~857 tokens) → way above 500 threshold
        List<Message> history = buildLargeHistory(3000);
        ToolExecutionResult delegateResult = buildResult(history);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);

        List<Message> compacted = result.conversationHistory();
        assertThat(compacted).hasSize(8); // Same number of messages

        // Middle tool response (index 3) should be compacted
        ToolResponseMessage middleTrm = (ToolResponseMessage) compacted.get(3);
        String data = middleTrm.getResponses().get(0).responseData();
        assertThat(data).contains("[compacted:");
        assertThat(data.length()).isLessThan(300); // Truncated to ~220 chars
    }

    @Test
    void aboveThreshold_preservesHeadAndTail() {
        List<Message> history = buildLargeHistory(3000);
        ToolExecutionResult delegateResult = buildResult(history);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        List<Message> compacted = result.conversationHistory();

        // Head (first 2) preserved
        assertThat(compacted.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(compacted.get(1)).isInstanceOf(UserMessage.class);

        // Tail (last 4) preserved — check the last tool response is NOT compacted
        Message lastMsg = compacted.get(compacted.size() - 1);
        assertThat(lastMsg).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage lastTrm = (ToolResponseMessage) lastMsg;
        assertThat(lastTrm.getResponses().get(0).responseData()).doesNotContain("[compacted:");
    }

    // ── Aggressive compaction ───────────────────────────────────────────────────

    @Test
    void aggressiveCompaction_triggeredWhenFirstPassStillOver() {
        // History with MANY large tool responses in the middle, so first-pass
        // compaction (200 char preview) is still over threshold
        List<Message> history = buildVeryLargeHistory();
        ToolExecutionResult delegateResult = buildResult(history);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        List<Message> compacted = result.conversationHistory();

        // Find a middle tool response — should have aggressive "[compacted]" prefix
        for (int i = 2; i < compacted.size() - 4; i++) {
            if (compacted.get(i) instanceof ToolResponseMessage trm) {
                String data = trm.getResponses().get(0).responseData();
                assertThat(data).startsWith("[compacted]");
            }
        }
    }

    // ── Tool response compaction details ────────────────────────────────────────

    @Test
    void compactToolResponse_shortDataPreserved() {
        // Data under 300 chars → not truncated
        List<Message> history = List.of(
                new SystemMessage("System"),
                new UserMessage("User"),
                new AssistantMessage("Reading small file"),
                toolResponse("call_1", "fs_read", "x".repeat(200)), // middle: 200 chars < 300
                new AssistantMessage("Writing result"),
                toolResponse("call_2", "fs_write", "wrote file"),
                new AssistantMessage("Done checking"),
                toolResponse("call_3", "fs_read", "final read")
        );
        ToolExecutionResult delegateResult = buildResult(history);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        List<Message> compacted = result.conversationHistory();

        // Middle tool response (index 3) has 200 chars < 300 → preserved as-is
        ToolResponseMessage middleTrm = (ToolResponseMessage) compacted.get(3);
        assertThat(middleTrm.getResponses().get(0).responseData()).isEqualTo("x".repeat(200));
    }

    @Test
    void compactToolResponse_longDataTruncated() {
        String longData = "A".repeat(5000);
        List<Message> history = List.of(
                new SystemMessage("System"),
                new UserMessage("User"),
                new AssistantMessage("Reading big file"),
                toolResponse("call_1", "fs_read", longData), // middle: 5000 chars > 300
                new AssistantMessage("Processing"),
                toolResponse("call_2", "fs_write", "ok"),
                new AssistantMessage("Final step"),
                toolResponse("call_3", "fs_read", "small")
        );
        ToolExecutionResult delegateResult = buildResult(history);
        when(delegate.executeToolCalls(any(), any())).thenReturn(delegateResult);

        ToolExecutionResult result = manager.executeToolCalls(prompt, chatResponse);
        List<Message> compacted = result.conversationHistory();

        ToolResponseMessage middleTrm = (ToolResponseMessage) compacted.get(3);
        String compactedData = middleTrm.getResponses().get(0).responseData();
        // First 200 chars of original preserved
        assertThat(compactedData).startsWith("A".repeat(200));
        // Indicator appended
        assertThat(compactedData).contains("[compacted: 5000 chars");
        assertThat(compactedData).contains("200 chars preview]");
    }

    // ── resolveToolDefinitions passthrough ───────────────────────────────────────

    @Test
    void resolveToolDefinitions_delegatesToWrapped() {
        List<ToolDefinition> expected = List.of();
        when(delegate.resolveToolDefinitions(any())).thenReturn(expected);

        List<ToolDefinition> result = manager.resolveToolDefinitions(null);

        assertThat(result).isSameAs(expected);
        verify(delegate).resolveToolDefinitions(null);
    }

    // ── PostProcessor (double-wrap prevention) ──────────────────────────────────

    @Test
    void postProcessor_wrapsToolCallingManager_notDouble() {
        CompactingToolCallingManagerPostProcessor postProcessor =
                new CompactingToolCallingManagerPostProcessor();

        // First wrap: ToolCallingManager → CompactingToolCallingManager
        Object wrapped = postProcessor.postProcessAfterInitialization(delegate, "tcm");
        assertThat(wrapped).isInstanceOf(CompactingToolCallingManager.class);

        // Second wrap attempt: CompactingToolCallingManager → should be returned as-is
        Object doubleWrapped = postProcessor.postProcessAfterInitialization(wrapped, "tcm");
        assertThat(doubleWrapped).isSameAs(wrapped); // Same instance, no double wrap

        // Non-ToolCallingManager bean → returned as-is
        String notTcm = "not a ToolCallingManager";
        Object passthrough = postProcessor.postProcessAfterInitialization(notTcm, "other");
        assertThat(passthrough).isSameAs(notTcm);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static ToolResponseMessage toolResponse(String id, String name, String data) {
        return new ToolResponseMessage(List.of(new ToolResponse(id, name, data)), Map.of());
    }

    private static ToolExecutionResult buildResult(List<Message> history) {
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(false)
                .build();
    }

    /**
     * Builds 8 messages: 2 head + 2 middle (assistant + big tool response) + 4 tail.
     * The middle tool response has {@code dataSize} chars.
     */
    private static List<Message> buildLargeHistory(int dataSize) {
        return List.of(
                new SystemMessage("You are a backend Java worker."),
                new UserMessage("Implement the feature as described."),
                // Middle: assistant + tool response (will be compacted)
                new AssistantMessage("Let me read the source file first."),
                toolResponse("call_old", "fs_read", "x".repeat(dataSize)),
                // Tail: 4 most recent messages (preserved)
                new AssistantMessage("Now I'll write the implementation."),
                toolResponse("call_write", "fs_write", "public class Foo {}"),
                new AssistantMessage("Let me verify the result."),
                toolResponse("call_verify", "fs_read", "public class Foo {}")
        );
    }

    /**
     * Builds 22 messages with many large tool responses in the middle,
     * forcing aggressive compaction (first-pass 200-char previews still over threshold).
     *
     * <p>Math: threshold=500 tokens ≈ 1750 chars. After first-pass compaction,
     * each tool response becomes ~220 chars. With 8 middle pairs, that's
     * 8×220=1760 + ~300 for assistants/head/tail ≈ 2060 chars > 1750 threshold.
     * This forces aggressive compaction.</p>
     */
    private static List<Message> buildVeryLargeHistory() {
        List<Message> history = new ArrayList<>();
        // Head (2)
        history.add(new SystemMessage("System prompt with detailed instructions for the worker."));
        history.add(new UserMessage("Build feature X with comprehensive implementation."));
        // Middle: 8 pairs of assistant + large tool response (16 messages)
        for (int i = 0; i < 8; i++) {
            history.add(new AssistantMessage("Reading source file number " + i + " for analysis."));
            history.add(toolResponse("call_" + i, "fs_read", "data".repeat(500)));
        }
        // Tail (4)
        history.add(new AssistantMessage("Writing result."));
        history.add(toolResponse("call_final_w", "fs_write", "done"));
        history.add(new AssistantMessage("Verifying."));
        history.add(toolResponse("call_final_r", "fs_read", "ok"));
        return history;
    }
}
