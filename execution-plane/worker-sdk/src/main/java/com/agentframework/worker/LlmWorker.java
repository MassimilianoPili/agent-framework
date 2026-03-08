package com.agentframework.worker;

import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.List;

/**
 * Intermediate base class for all LLM-based workers.
 *
 * <p>Provides a standard {@link #execute(AgentContext, ChatClient)} implementation
 * that builds a user prompt from {@link #instructions()} and {@link #resultSchema()},
 * calls the ChatClient, records token usage, and returns the response.</p>
 *
 * <p>Subclasses only need to provide {@link #instructions()} and {@link #resultSchema()}.
 * Workers that need custom execution logic (e.g., profile-based prompt routing) can
 * override {@code execute()} directly.</p>
 *
 * <p>Programmatic workers (no LLM) should extend {@link AbstractWorker} instead.</p>
 */
public abstract class LlmWorker extends AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(LlmWorker.class);

    protected LlmWorker(AgentContextBuilder contextBuilder,
                         WorkerChatClientFactory chatClientFactory,
                         WorkerResultProducer resultProducer,
                         List<WorkerInterceptor> interceptors) {
        super(contextBuilder, chatClientFactory, resultProducer, interceptors);
    }

    /**
     * Worker-specific instructions injected into the user prompt.
     * Typically a multi-line text block describing what the LLM should do.
     */
    protected abstract String instructions();

    /**
     * Expected JSON result schema, appended to the instructions.
     * The LLM is asked to return its result conforming to this structure.
     */
    protected abstract String resultSchema();

    /**
     * Standard LLM execution: build prompt, call ChatClient, record tokens, return response.
     *
     * <p>This default implementation covers the common pattern shared by all generated workers.
     * Override for custom behavior (e.g., multi-turn conversations, streaming).</p>
     */
    @Override
    protected String execute(AgentContext context, ChatClient chatClient)
            throws WorkerExecutionException {

        String userPrompt = buildStandardUserPrompt(context,
            instructions() + "\n\nReturn your result as JSON with the following structure:\n```json\n"
            + resultSchema() + "\n```");

        log.info("[{}] Executing task '{}' with {} dependency results",
                 workerType(), context.taskKey(), context.dependencyResults().size());

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(context.systemPrompt())
                .user(userPrompt)
                .call()
                .chatResponse();

            String response = chatResponse.getResult().getOutput().getText();
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                recordTokenUsage(chatResponse.getMetadata().getUsage());
            }

            log.info("[{}] Task '{}' completed, response length: {} chars",
                     workerType(), context.taskKey(), response != null ? response.length() : 0);
            return response;

        } catch (Exception e) {
            throw new WorkerExecutionException(
                workerType() + " worker execution failed for task " + context.taskKey(), e);
        }
    }
}
