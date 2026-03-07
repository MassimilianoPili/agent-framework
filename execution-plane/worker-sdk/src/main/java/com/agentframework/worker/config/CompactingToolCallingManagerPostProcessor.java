package com.agentframework.worker.config;

import com.agentframework.worker.claude.CompactingToolCallingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Post-processor that wraps the default {@link ToolCallingManager} with a
 * {@link CompactingToolCallingManager} to prevent context window overflow
 * during the agentic tool-calling loop.
 *
 * <p>This is registered as a bean in {@link WorkerAutoConfiguration} and
 * activates only when the {@code agent.worker.task-topic} property is set
 * (i.e., only in worker applications).</p>
 */
public class CompactingToolCallingManagerPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CompactingToolCallingManagerPostProcessor.class);

    /** Default context window for Claude models. */
    private static final int DEFAULT_MAX_TOKENS = 200_000;

    /** Trigger compaction at 60% of context window. */
    private static final double DEFAULT_COMPACT_THRESHOLD = 0.60;

    private final int maxTokens;
    private final double compactThreshold;

    public CompactingToolCallingManagerPostProcessor() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_COMPACT_THRESHOLD);
    }

    public CompactingToolCallingManagerPostProcessor(int maxTokens, double compactThreshold) {
        this.maxTokens = maxTokens;
        this.compactThreshold = compactThreshold;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ToolCallingManager tcm && !(bean instanceof CompactingToolCallingManager)) {
            log.info("Wrapping ToolCallingManager '{}' with CompactingToolCallingManager " +
                    "(maxTokens={}, threshold={}%)", beanName, maxTokens, (int)(compactThreshold * 100));
            return new CompactingToolCallingManager(tcm, maxTokens, compactThreshold);
        }
        return bean;
    }
}
