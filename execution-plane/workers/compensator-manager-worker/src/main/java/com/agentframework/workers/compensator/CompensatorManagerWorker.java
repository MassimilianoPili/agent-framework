package com.agentframework.workers.compensator;

import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.ToolAllowlist;
import com.agentframework.worker.WorkerExecutionException;
import com.agentframework.worker.claude.WorkerChatClientFactory;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.context.AgentContextBuilder;
import com.agentframework.worker.interceptor.WorkerInterceptor;
import com.agentframework.worker.messaging.WorkerResultProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Compensator Manager Worker.
 *
 * <p>Executes compensating transactions to undo the effects of a previously completed task.
 * Compensation operations include git revert, file restoration, and dependency cleanup.
 *
 * <p>The task description carries the compensation context: what was originally done
 * (from the failed/reverted item's result), and what must be undone. The worker uses
 * {@code git-mcp} tools to perform the rollback operations.</p>
 *
 * <p>Worker profile: {@code compensator-manager} on topic {@code agent-tasks}.
 */
@Component
public class CompensatorManagerWorker extends AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(CompensatorManagerWorker.class);

    /** git-mcp tools for rollback operations. */
    private static final List<String> TOOL_ALLOWLIST = List.of(
        "git_status",
        "git_log",
        "git_diff",
        "git_revert",
        "git_checkout",
        "git_stash",
        "read_file",
        "write_file",
        "list_directory"
    );

    private static final String INSTRUCTIONS = """
            You are the Compensator Manager Worker.

            Your job is to UNDO the changes made by a previously completed task.
            The task description contains:
            - "original_task": what was done (title and description of the task being compensated)
            - "original_result": the JSON result returned by the original task
            - "compensation_reason": why compensation is required

            Your goal is to restore the codebase to its state BEFORE the original task was executed.

            Steps:
            1. Understand what was changed by reviewing the original task and its result.
            2. Use git_log to find the commit(s) made by the original task.
            3. Use git_revert (or git_checkout for individual files) to undo those changes.
            4. Verify the rollback is complete using git_status and git_diff.
            5. Return a structured JSON report of the compensation.

            Return your result STRICTLY as JSON (no markdown):
            {
              "compensated": true,
              "commits_reverted": ["sha1", "sha2"],
              "files_restored": ["path/to/file1", "path/to/file2"],
              "compensation_summary": "Brief description of what was undone",
              "verification": "git status / diff output confirming clean state"
            }

            If compensation is NOT possible (e.g., changes were already overwritten), return:
            {
              "compensated": false,
              "reason": "Why compensation could not be performed",
              "partial_actions": "Description of any partial cleanup done"
            }
            """;

    public CompensatorManagerWorker(AgentContextBuilder contextBuilder,
                                    WorkerChatClientFactory chatClientFactory,
                                    WorkerResultProducer resultProducer,
                                    List<WorkerInterceptor> interceptors) {
        super(contextBuilder, chatClientFactory, resultProducer, interceptors);
    }

    @Override
    public String workerType() {
        return "COMPENSATOR_MANAGER";
    }

    @Override
    protected String systemPromptFile() {
        return "agents/compensator-manager.agent.md";
    }

    @Override
    protected ToolAllowlist toolAllowlist() {
        return new ToolAllowlist.Explicit(TOOL_ALLOWLIST);
    }

    @Override
    protected List<String> skillPaths() {
        return List.of("skills/git-operations");
    }

    @Override
    protected String execute(AgentContext context, ChatClient chatClient)
            throws WorkerExecutionException {

        log.info("[COMPENSATOR_MANAGER] Starting compensation for task '{}'", context.taskKey());

        String userPrompt = buildStandardUserPrompt(context, INSTRUCTIONS);

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(context.systemPrompt())
                .user(userPrompt)
                .call()
                .chatResponse();

            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                recordTokenUsage(chatResponse.getMetadata().getUsage());
            }

            String resultJson = chatResponse.getResult().getOutput().getText();
            log.info("[COMPENSATOR_MANAGER] Compensation completed for task '{}', result length: {} chars",
                     context.taskKey(), resultJson != null ? resultJson.length() : 0);
            return resultJson;

        } catch (Exception e) {
            throw new WorkerExecutionException(
                "COMPENSATOR_MANAGER worker execution failed for task " + context.taskKey(), e);
        }
    }
}
