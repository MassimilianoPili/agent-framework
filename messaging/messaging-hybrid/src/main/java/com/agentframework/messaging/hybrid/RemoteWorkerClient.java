package com.agentframework.messaging.hybrid;

import com.agentframework.messaging.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for dispatching tasks to remote worker JVMs and sending cancel signals.
 *
 * <p>Uses {@link java.net.http.HttpClient} (JDK 21, zero external dependencies) with
 * virtual threads for non-blocking I/O.
 *
 * <p>Task dispatch is fire-and-forget: the remote worker returns 202 Accepted immediately
 * and processes the task asynchronously. Results flow back via HTTP callback to the
 * orchestrator's {@code POST /internal/results} endpoint.
 */
public class RemoteWorkerClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkerClient.class);

    private final HybridMessagingProperties properties;
    private final HttpClient httpClient;

    public RemoteWorkerClient(HybridMessagingProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeout()))
                .build();
    }

    /**
     * Dispatches a task to a remote worker JVM.
     *
     * @param envelope the message envelope containing the serialized AgentTask
     * @throws RemoteWorkerException if the remote worker is unreachable or returns an error
     */
    public void dispatch(MessageEnvelope envelope) {
        String workerType = envelope.properties().get("workerType");
        String baseUrl = resolveEndpoint(workerType);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/tasks"))
                .header("Content-Type", "application/json")
                .header("X-Task-Key", envelope.properties().getOrDefault("taskKey", ""))
                .header("X-Worker-Type", workerType)
                .header("X-Plan-Id", envelope.properties().getOrDefault("planId", ""))
                .timeout(Duration.ofMillis(properties.getReadTimeout()))
                .POST(HttpRequest.BodyPublishers.ofString(envelope.body()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 202 || response.statusCode() == 200) {
                log.info("Dispatched task '{}' to remote worker {} at {}",
                        envelope.properties().get("taskKey"), workerType, baseUrl);
            } else if (response.statusCode() == 409) {
                log.warn("Task '{}' already running on remote worker {} (409 Conflict)",
                        envelope.properties().get("taskKey"), workerType);
            } else {
                throw new RemoteWorkerException(
                        "Remote worker %s returned HTTP %d: %s".formatted(
                                workerType, response.statusCode(), response.body()));
            }
        } catch (RemoteWorkerException e) {
            throw e;
        } catch (Exception e) {
            throw new RemoteWorkerException(
                    "Failed to dispatch task to remote worker %s at %s: %s".formatted(
                            workerType, baseUrl, e.getMessage()), e);
        }
    }

    /**
     * Sends a cancel signal to a remote worker JVM.
     *
     * @param workerType the worker type (to resolve the endpoint)
     * @param taskKey the task key to cancel
     * @return true if the remote worker acknowledged the cancellation
     */
    public boolean cancel(String workerType, String taskKey) {
        String baseUrl = resolveEndpoint(workerType);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/tasks/" + taskKey + "/cancel"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.info("Cancelled task '{}' on remote worker {}", taskKey, workerType);
                return true;
            } else if (response.statusCode() == 404) {
                log.debug("Task '{}' not found on remote worker {} (already completed?)", taskKey, workerType);
                return false;
            } else {
                log.warn("Unexpected response cancelling task '{}' on {}: HTTP {}",
                        taskKey, workerType, response.statusCode());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to cancel task '{}' on remote worker {}: {}", taskKey, workerType, e.getMessage());
            return false;
        }
    }

    /**
     * Checks the health of a remote worker JVM.
     *
     * @param workerType the worker type to check
     * @return true if the worker responded with 200
     */
    public boolean healthCheck(String workerType) {
        String baseUrl = resolveEndpoint(workerType);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Health check failed for remote worker {} at {}: {}", workerType, baseUrl, e.getMessage());
            return false;
        }
    }

    private String resolveEndpoint(String workerType) {
        String endpoint = properties.getEndpoints().get(workerType);
        if (endpoint == null) {
            throw new RemoteWorkerException(
                    "No endpoint configured for remote worker type: " + workerType
                    + ". Available: " + properties.getEndpoints().keySet());
        }
        return endpoint;
    }
}
