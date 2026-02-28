package com.agentframework.worker.interceptor;

import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import org.springframework.core.Ordered;

/**
 * Pipeline hook for cross-cutting concerns in worker execution.
 *
 * <p>Interceptors are invoked in order around {@code AbstractWorker.execute()}:</p>
 * <ol>
 *   <li>{@link #beforeExecute} — modify context, add metrics, validate preconditions</li>
 *   <li>Worker.execute()</li>
 *   <li>{@link #afterExecute} — transform result, log metrics, trigger side-effects</li>
 *   <li>{@link #onError} — handle failures, record diagnostics</li>
 * </ol>
 *
 * <p>Ordering follows Spring's {@link Ordered} contract (lower value = higher priority).</p>
 */
public interface WorkerInterceptor extends Ordered {

    /**
     * Called before worker execution. May modify the context.
     * @return the (possibly modified) context to use for execution
     */
    default AgentContext beforeExecute(AgentContext ctx, AgentTask task) {
        return ctx;
    }

    /**
     * Called after successful worker execution. May transform the result.
     * @return the (possibly transformed) result
     */
    default String afterExecute(AgentContext ctx, String result, AgentTask task) {
        return result;
    }

    /**
     * Called when worker execution throws an exception.
     */
    default void onError(AgentContext ctx, Exception e, AgentTask task) {
    }

    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
