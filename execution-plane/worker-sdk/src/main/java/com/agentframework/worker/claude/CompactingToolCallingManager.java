package com.agentframework.worker.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegating {@link ToolCallingManager} that compacts conversation history
 * when it grows beyond a configurable token threshold.
 *
 * <p>Spring AI's agentic loop (in {@code AnthropicChatModel.internalCall})
 * is recursive: after each tool execution, it calls itself with the full
 * conversation history (all previous messages + new tool results). Without
 * compaction, this history grows without bound until it exceeds the model's
 * context window (200K tokens for Claude).</p>
 *
 * <p>This wrapper intercepts {@link #executeToolCalls} and, when the
 * resulting conversation history exceeds the threshold, replaces older
 * tool response contents with a compact summary (just the tool name and
 * a truncated preview). This is analogous to Claude Code's "automatic
 * context compression" feature.</p>
 *
 * <p>Token estimation uses the 4 chars ≈ 1 token heuristic, which is
 * conservative for English/code and slightly under for other languages.</p>
 */
public class CompactingToolCallingManager implements ToolCallingManager {

    private static final Logger log = LoggerFactory.getLogger(CompactingToolCallingManager.class);

    /** Characters per token estimate (conservative for English + code). */
    private static final double CHARS_PER_TOKEN = 3.5;

    private final ToolCallingManager delegate;
    private final int maxTokens;
    private final double compactThreshold;

    /**
     * @param delegate       the actual ToolCallingManager that executes tools
     * @param maxTokens      model's context window size (e.g. 200_000 for Claude)
     * @param compactThreshold fraction of maxTokens at which compaction triggers (e.g. 0.65)
     */
    public CompactingToolCallingManager(ToolCallingManager delegate, int maxTokens, double compactThreshold) {
        this.delegate = delegate;
        this.maxTokens = maxTokens;
        this.compactThreshold = compactThreshold;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, org.springframework.ai.chat.model.ChatResponse chatResponse) {
        ToolExecutionResult result = delegate.executeToolCalls(prompt, chatResponse);

        List<Message> history = result.conversationHistory();
        int estimatedTokens = estimateTokens(history);

        int threshold = (int) (maxTokens * compactThreshold);
        if (estimatedTokens > threshold) {
            log.info("Context compaction triggered: ~{} tokens > {} threshold ({} messages)",
                    estimatedTokens, threshold, history.size());
            List<Message> compacted = compactHistory(history, threshold);
            int afterTokens = estimateTokens(compacted);
            log.info("After compaction: ~{} tokens ({} messages)", afterTokens, compacted.size());
            return ToolExecutionResult.builder()
                    .conversationHistory(compacted)
                    .returnDirect(result.returnDirect())
                    .build();
        }

        return result;
    }

    /**
     * Estimates total token count for a message list.
     * Uses the 3.5 chars/token heuristic.
     */
    private int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message msg : messages) {
            totalChars += messageCharCount(msg);
        }
        return (int) (totalChars / CHARS_PER_TOKEN);
    }

    private int messageCharCount(Message msg) {
        if (msg instanceof ToolResponseMessage trm) {
            int count = 0;
            for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                count += safeLength(resp.responseData());
                count += safeLength(resp.name());
            }
            return count;
        }
        return safeLength(msg.getText());
    }

    private int safeLength(String s) {
        return s != null ? s.length() : 0;
    }

    /**
     * Compacts the conversation history by truncating older tool response
     * messages while preserving the system prompt, user prompt, and the
     * most recent tool interactions.
     *
     * <p>Strategy:</p>
     * <ol>
     *   <li>Keep the first 2 messages intact (system + user prompt)</li>
     *   <li>Keep the last N message pairs (recent tool interactions)</li>
     *   <li>For messages in between, replace ToolResponseMessage contents
     *       with a compact summary</li>
     * </ol>
     */
    private List<Message> compactHistory(List<Message> history, int tokenThreshold) {
        if (history.size() <= 4) {
            return history; // Too few messages to compact
        }

        // Keep first 2 messages (system context + user prompt) and last 4 messages
        // (the most recent assistant+tool pair, needed for the model to continue)
        int keepHead = 2;
        int keepTail = 4;

        if (history.size() <= keepHead + keepTail) {
            return history;
        }

        ArrayList<Message> compacted = new ArrayList<>(history.size());

        // Head: keep as-is
        for (int i = 0; i < keepHead; i++) {
            compacted.add(history.get(i));
        }

        // Middle: compact tool responses
        int middleEnd = history.size() - keepTail;
        for (int i = keepHead; i < middleEnd; i++) {
            Message msg = history.get(i);
            if (msg instanceof ToolResponseMessage trm) {
                compacted.add(compactToolResponse(trm));
            } else {
                // AssistantMessage with tool_use — keep as-is (model needs it for context)
                compacted.add(msg);
            }
        }

        // Tail: keep as-is (most recent interactions)
        for (int i = middleEnd; i < history.size(); i++) {
            compacted.add(history.get(i));
        }

        // If still over threshold, be more aggressive: compact ALL middle tool responses
        // to minimal summaries
        int estimatedAfter = estimateTokens(compacted);
        if (estimatedAfter > tokenThreshold) {
            log.info("First pass still over threshold (~{} tokens), applying aggressive compaction", estimatedAfter);
            return aggressiveCompact(compacted, keepHead, compacted.size() - keepTail);
        }

        return compacted;
    }

    /**
     * Compacts a single ToolResponseMessage by truncating each tool response
     * to a brief summary with the first 200 chars of content.
     */
    private ToolResponseMessage compactToolResponse(ToolResponseMessage trm) {
        List<ToolResponseMessage.ToolResponse> compactedResponses = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
            String data = resp.responseData();
            if (data != null && data.length() > 300) {
                // Keep first 200 chars + indicator
                String summary = data.substring(0, 200)
                        + "\n... [compacted: " + data.length() + " chars → 200 chars preview]";
                compactedResponses.add(new ToolResponseMessage.ToolResponse(
                        resp.id(), resp.name(), summary));
            } else {
                compactedResponses.add(resp);
            }
        }
        return new ToolResponseMessage(compactedResponses, trm.getMetadata());
    }

    /**
     * Aggressive compaction: replace all middle tool responses with a one-line summary.
     */
    private List<Message> aggressiveCompact(List<Message> messages, int fromIndex, int toIndex) {
        ArrayList<Message> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (i >= fromIndex && i < toIndex && msg instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> minimal = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    String brief = "[compacted] " + resp.name() + " executed successfully";
                    minimal.add(new ToolResponseMessage.ToolResponse(resp.id(), resp.name(), brief));
                }
                result.add(new ToolResponseMessage(minimal, trm.getMetadata()));
            } else {
                result.add(msg);
            }
        }
        return result;
    }
}
