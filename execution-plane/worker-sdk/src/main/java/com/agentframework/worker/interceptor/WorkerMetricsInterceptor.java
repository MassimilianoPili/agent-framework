package com.agentframework.worker.interceptor;

import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Structured metrics logging interceptor for worker execution.
 *
 * <p>Populates the MDC (Mapped Diagnostic Context) with task metadata at the start
 * of each execution so that all subsequent log lines within the task carry consistent
 * tracing fields. Clears the MDC on completion or failure to prevent leaks between
 * tasks processed by the same thread.</p>
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} to ensure MDC is set before any
 * other interceptor or the execute() method runs.</p>
 *
 * <p>Log events:</p>
 * <ul>
 *   <li>{@code TASK_START} — emitted in beforeExecute</li>
 *   <li>{@code TASK_SUCCESS} — emitted in afterExecute</li>
 *   <li>{@code TASK_FAILURE} — emitted in onError</li>
 * </ul>
 */
@Component
public class WorkerMetricsInterceptor implements WorkerInterceptor {

    private static final Logger metrics = LoggerFactory.getLogger("metrics.worker");

    @Override
    public AgentContext beforeExecute(AgentContext ctx, AgentTask task) {
        MDC.put("task_key", ctx.taskKey());
        MDC.put("worker_type", task.workerType() != null ? task.workerType() : "UNKNOWN");
        MDC.put("worker_profile", task.workerProfile() != null ? task.workerProfile() : "");
        MDC.put("attempt", String.valueOf(task.attemptNumber() != null ? task.attemptNumber() : 1));
        MDC.put("plan_id", task.planId() != null ? task.planId().toString() : "");
        MDC.put("trace_id", task.traceId() != null ? task.traceId().toString() : "");
        MDC.put("policy_active", String.valueOf(ctx.hasPolicy()));

        metrics.info("TASK_START task={} worker={} plan={} attempt={}",
                ctx.taskKey(), task.workerType(), task.planId(), task.attemptNumber());
        return ctx;
    }

    @Override
    public String afterExecute(AgentContext ctx, String result, AgentTask task) {
        boolean hasResult = result != null && !result.isBlank();
        metrics.info("TASK_SUCCESS task={} worker={} has_result={} has_context={}",
                ctx.taskKey(), task.workerType(), hasResult, ctx.hasRelevantFiles());
        MDC.clear();
        return result;
    }

    @Override
    public void onError(AgentContext ctx, Exception e, AgentTask task) {
        metrics.error("TASK_FAILURE task={} worker={} error={}",
                ctx != null ? ctx.taskKey() : "unknown",
                task.workerType(),
                e.getMessage());
        MDC.clear();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
