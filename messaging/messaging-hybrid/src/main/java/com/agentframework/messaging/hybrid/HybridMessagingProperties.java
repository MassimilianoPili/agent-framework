package com.agentframework.messaging.hybrid;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for hybrid messaging mode.
 *
 * <p>Defines which worker types are dispatched remotely and their endpoint URLs.
 * Worker types NOT in {@code remoteTypes} are dispatched in-process via the
 * {@link com.agentframework.messaging.inprocess.InProcessMessageBroker}.
 */
@ConfigurationProperties(prefix = "worker-runtime")
public class HybridMessagingProperties {

    /**
     * Worker type names dispatched to remote JVMs (e.g., BE, FE, DBA, AI_TASK).
     */
    private Set<String> remoteTypes = Set.of();

    /**
     * Mapping of worker type name to base URL (e.g., BE=http://be-worker:8100).
     */
    private Map<String, String> endpoints = Map.of();

    /**
     * HTTP connect timeout in milliseconds (default 5s).
     */
    private int connectTimeout = 5000;

    /**
     * HTTP read timeout in milliseconds (default 5 min — LLM calls can be slow).
     */
    private int readTimeout = 300_000;

    /**
     * Health check interval in seconds (default 30s).
     */
    private int healthCheckIntervalSeconds = 30;

    public Set<String> getRemoteTypes() { return remoteTypes; }
    public void setRemoteTypes(Set<String> remoteTypes) { this.remoteTypes = remoteTypes; }

    public Map<String, String> getEndpoints() { return endpoints; }
    public void setEndpoints(Map<String, String> endpoints) { this.endpoints = endpoints; }

    public int getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(int connectTimeout) { this.connectTimeout = connectTimeout; }

    public int getReadTimeout() { return readTimeout; }
    public void setReadTimeout(int readTimeout) { this.readTimeout = readTimeout; }

    public int getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
    public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }
}
