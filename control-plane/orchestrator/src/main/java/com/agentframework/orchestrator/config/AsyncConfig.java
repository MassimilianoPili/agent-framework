package com.agentframework.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Shared async thread pool for long-running background operations in the orchestrator.
 *
 * <p>Used by {@code QualityGateService} (LLM-backed report generation, several minutes per plan)
 * and {@code PlanSnapshotListener} (DB-backed snapshot creation). Keeping these on a separate pool
 * prevents blocking the Service Bus message handler threads.</p>
 */
@Configuration
public class AsyncConfig {

    @Bean("orchestratorAsyncExecutor")
    public Executor orchestratorAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("orch-async-");
        executor.initialize();
        return executor;
    }
}
