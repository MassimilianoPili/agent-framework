package com.agentframework.workers.taskmanager;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Task Manager Worker.
 *
 * <p>Fetches the canonical issue from the external issue tracker using the
 * {@code tracker-mcp} tool. The result — a structured JSON snapshot of the
 * issue (title, description, acceptance criteria, labels, comments) — is:
 * <ol>
 *   <li>Returned as the standard task result (stored in {@code plan_items.result})</li>
 *   <li>Also saved to {@code plan_items.issue_snapshot} via the orchestrator's
 *       internal PUT endpoint, so it's indexable without parsing the result blob</li>
 * </ol>
 *
 * <p>Worker profile: {@code task-manager} on topic {@code agent-tasks}.
 */
@Component
public class TaskManagerWorker extends AbstractWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskManagerWorker.class);

    private static final List<String> TOOL_ALLOWLIST = List.of(
        "tracker_get_issue",
        "tracker_list_comments",
        "tracker_get_labels"
    );

    private static final String INSTRUCTIONS = """
            You are the Task Manager Worker.

            Your job is to fetch the full details of the issue referenced in the task
            description from the external issue tracker and produce a structured snapshot.

            Steps:
            1. Extract the issue identifier from the task description (e.g., "PROJ-123", "#42").
            2. Use the tracker_get_issue tool to fetch the issue details.
            3. Optionally use tracker_list_comments to fetch the last 5 comments.
            4. Return a structured JSON snapshot with all relevant information.

            Return your result STRICTLY as JSON (no markdown):
            {
              "issue_id": "PROJ-123",
              "title": "issue title",
              "description": "full issue description",
              "acceptance_criteria": ["criterion 1", "criterion 2"],
              "labels": ["label1", "label2"],
              "status": "In Progress",
              "assignee": "username or null",
              "priority": "High",
              "recent_comments": [
                {"author": "user", "body": "comment text", "created_at": "ISO-8601"}
              ],
              "summary": "one-sentence summary of what needs to be done"
            }
            """;

    private final RestClient orchestratorClient;

    public TaskManagerWorker(AgentContextBuilder contextBuilder,
                             WorkerChatClientFactory chatClientFactory,
                             WorkerResultProducer resultProducer,
                             List<WorkerInterceptor> interceptors,
                             @Value("${agent.orchestrator.base-url:http://orchestrator:8080}") String orchestratorBaseUrl) {
        super(contextBuilder, chatClientFactory, resultProducer, interceptors);
        this.orchestratorClient = RestClient.builder()
                .baseUrl(orchestratorBaseUrl)
                .build();
    }

    @Override
    public String workerType() {
        return "TASK_MANAGER";
    }

    @Override
    protected String systemPromptFile() {
        return "agents/task-manager.agent.md";
    }

    @Override
    protected ToolAllowlist toolAllowlist() {
        return new ToolAllowlist.Explicit(TOOL_ALLOWLIST);
    }

    @Override
    protected List<String> skillPaths() {
        return List.of();
    }

    @Override
    protected String execute(AgentContext context, ChatClient chatClient)
            throws WorkerExecutionException {

        log.info("[TASK_MANAGER] Fetching issue snapshot for task '{}'", context.taskKey());

        String userPrompt = buildStandardUserPrompt(context, INSTRUCTIONS);

        try {
            ChatResponse chatResponse = chatClient.prompt()
                .system(context.systemPrompt())
                .user(userPrompt)
                .call()
                .chatResponse();

            String snapshotJson = chatResponse.getResult().getOutput().getText();

            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                recordTokenUsage(chatResponse.getMetadata().getUsage());
            }

            // Persist the snapshot to plan_items.issue_snapshot via the orchestrator's
            // internal endpoint so downstream workers can reference it by field — not
            // by parsing the full result blob.
            saveIssueSnapshot(context, snapshotJson);

            log.info("[TASK_MANAGER] Task '{}' completed, snapshot length: {} chars",
                     context.taskKey(), snapshotJson != null ? snapshotJson.length() : 0);
            return snapshotJson;

        } catch (Exception e) {
            throw new WorkerExecutionException(
                "TASK_MANAGER worker execution failed for task " + context.taskKey(), e);
        }
    }

    /**
     * Saves the issue snapshot to the orchestrator's DB via internal REST call.
     * Fire-and-forget: if this fails, the result is still returned to the caller.
     * The snapshot can be re-derived from the task result if needed.
     */
    private void saveIssueSnapshot(AgentContext context, String snapshotJson) {
        try {
            orchestratorClient.put()
                .uri("/api/v1/plans/{planId}/items/{itemId}/issue-snapshot",
                     context.planId(), context.itemId())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("snapshotJson", snapshotJson))
                .retrieve()
                .toBodilessEntity();
            log.debug("[TASK_MANAGER] Issue snapshot saved for item {}", context.itemId());
        } catch (Exception e) {
            log.warn("[TASK_MANAGER] Failed to persist issue snapshot for item {}: {}",
                     context.itemId(), e.getMessage());
        }
    }
}
