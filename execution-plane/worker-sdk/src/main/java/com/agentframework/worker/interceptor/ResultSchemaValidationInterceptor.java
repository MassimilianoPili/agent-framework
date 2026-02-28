package com.agentframework.worker.interceptor;

import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Validates that worker output is well-formed JSON.
 *
 * <p>Runs at {@link Ordered#LOWEST_PRECEDENCE} so it inspects the final result
 * after all other interceptors have transformed it. Fail-open: if the result is
 * not valid JSON, a warning is logged but the original result is returned unchanged.
 * This ensures that non-JSON outputs (plain text, Markdown) are not silently dropped.</p>
 *
 * <p>Workers are expected to produce JSON objects as their result. This interceptor
 * acts as an observability layer — it does not enforce structure, it only reports
 * deviations so they can be addressed in the worker's system prompt or skill file.</p>
 */
@Component
public class ResultSchemaValidationInterceptor implements WorkerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ResultSchemaValidationInterceptor.class);

    private final ObjectMapper objectMapper;

    public ResultSchemaValidationInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String afterExecute(AgentContext ctx, String result, AgentTask task) {
        if (result == null || result.isBlank()) {
            log.warn("[{}] Worker produced null/empty result (worker={})", ctx.taskKey(), task.workerType());
            return result;
        }

        try {
            objectMapper.readTree(result);
            log.debug("[{}] Result is valid JSON ({} chars)", ctx.taskKey(), result.length());
        } catch (Exception e) {
            String preview = result.length() > 200 ? result.substring(0, 200) + "..." : result;
            log.warn("[{}] Worker result is not valid JSON (worker={}): {}. Preview: {}",
                    ctx.taskKey(), task.workerType(), e.getMessage(), preview);
        }

        return result; // fail-open: always return original result
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
