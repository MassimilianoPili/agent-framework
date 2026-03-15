package com.agentframework.orchestrator.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Configuration for the Execution Runtime subsystem.
 *
 * <pre>
 * execution-runtime:
 *   enabled: false
 *   pool:
 *     size: 4
 *     languages: [java, go, python, node]
 *     eviction-policy: lru
 *     health-check-interval-ms: 300000
 *   timeout:
 *     soft-seconds: 60
 *     hard-seconds: 90
 *   resources:
 *     max-memory-mb: 512
 *     max-cpu-seconds: 120
 * </pre>
 *
 * @param pool      container pool configuration
 * @param timeout   execution timeout configuration
 * @param resources resource limits for sandbox containers
 */
@ConfigurationProperties(prefix = "execution-runtime")
public record ExecutionRuntimeConfig(
        PoolConfig pool,
        TimeoutConfig timeout,
        ResourceConfig resources
) {
    public ExecutionRuntimeConfig {
        if (pool == null) pool = new PoolConfig(4, List.of("java", "go", "python", "node"), "lru", null, 300000);
        if (timeout == null) timeout = new TimeoutConfig(60, 90);
        if (resources == null) resources = new ResourceConfig(512, 120);
    }

    /**
     * @param size                    max warm images in pool
     * @param languages               languages to pre-warm
     * @param evictionPolicy          eviction strategy (currently only "lru")
     * @param imageOverrides          optional language→image overrides
     * @param healthCheckIntervalMs   ms between health checks
     */
    public record PoolConfig(
            int size,
            List<String> languages,
            String evictionPolicy,
            Map<String, String> imageOverrides,
            long healthCheckIntervalMs
    ) {
        public PoolConfig {
            if (size <= 0) size = 4;
            if (languages == null) languages = List.of("java", "go", "python", "node");
            if (evictionPolicy == null) evictionPolicy = "lru";
            if (healthCheckIntervalMs <= 0) healthCheckIntervalMs = 300000;
        }
    }

    /**
     * @param softSeconds SIGTERM timeout (graceful shutdown window)
     * @param hardSeconds SIGKILL timeout (forced kill after soft timeout)
     */
    public record TimeoutConfig(int softSeconds, int hardSeconds) {
        public TimeoutConfig {
            if (softSeconds <= 0) softSeconds = 60;
            if (hardSeconds <= 0) hardSeconds = 90;
        }
    }

    /**
     * @param maxMemoryMb    max memory per container in MB
     * @param maxCpuSeconds  max CPU time per execution
     */
    public record ResourceConfig(int maxMemoryMb, int maxCpuSeconds) {
        public ResourceConfig {
            if (maxMemoryMb <= 0) maxMemoryMb = 512;
            if (maxCpuSeconds <= 0) maxCpuSeconds = 120;
        }
    }
}
