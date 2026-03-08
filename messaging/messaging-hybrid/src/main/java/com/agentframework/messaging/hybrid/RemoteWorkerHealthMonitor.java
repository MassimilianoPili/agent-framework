package com.agentframework.messaging.hybrid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically checks the health of remote worker JVMs.
 *
 * <p>Pings {@code GET /health} on each configured remote endpoint and maintains
 * a live status map. Unhealthy workers are logged as warnings.
 */
public class RemoteWorkerHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkerHealthMonitor.class);

    private final RemoteWorkerClient client;
    private final HybridMessagingProperties properties;
    private final Map<String, Boolean> healthStatus = new ConcurrentHashMap<>();

    public RemoteWorkerHealthMonitor(RemoteWorkerClient client, HybridMessagingProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${worker-runtime.health-check-interval-seconds:30}000")
    public void checkHealth() {
        Set<String> remoteTypes = properties.getRemoteTypes();
        if (remoteTypes.isEmpty()) return;

        for (String workerType : remoteTypes) {
            if (!properties.getEndpoints().containsKey(workerType)) continue;

            boolean healthy = client.healthCheck(workerType);
            Boolean previous = healthStatus.put(workerType, healthy);

            if (!healthy && (previous == null || previous)) {
                log.warn("Remote worker {} is DOWN (endpoint: {})",
                        workerType, properties.getEndpoints().get(workerType));
            } else if (healthy && previous != null && !previous) {
                log.info("Remote worker {} is back UP", workerType);
            }
        }
    }

    /**
     * Returns the current health status of all monitored remote workers.
     */
    public Map<String, Boolean> getHealthStatus() {
        return Map.copyOf(healthStatus);
    }

    /**
     * Returns whether a specific remote worker type is currently healthy.
     * Returns true if status is unknown (not yet checked).
     */
    public boolean isHealthy(String workerType) {
        return healthStatus.getOrDefault(workerType, true);
    }
}
