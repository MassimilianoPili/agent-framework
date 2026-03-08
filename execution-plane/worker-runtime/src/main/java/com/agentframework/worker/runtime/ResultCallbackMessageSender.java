package com.agentframework.worker.runtime;

import com.agentframework.messaging.MessageEnvelope;
import com.agentframework.messaging.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * {@link MessageSender} implementation for the standalone worker-runtime.
 *
 * <p>In the worker-runtime, there is no message broker. Results and events are
 * delivered to the orchestrator via HTTP callback ({@code POST /internal/results}).
 * The orchestrator's {@code ResultCallbackController} injects them into the
 * in-process broker for unified consumption by {@code AgentResultConsumer}.
 *
 * <p>This adapter bridges the existing {@link com.agentframework.worker.messaging.WorkerResultProducer}
 * (which depends on {@code MessageSender}) with the HTTP callback mechanism,
 * keeping {@code AbstractWorker} completely unaware of the deployment mode.
 */
public class ResultCallbackMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(ResultCallbackMessageSender.class);

    private final String orchestratorUrl;
    private final HttpClient httpClient;

    public ResultCallbackMessageSender(String orchestratorUrl) {
        this.orchestratorUrl = orchestratorUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void send(MessageEnvelope envelope) {
        String taskKey = envelope.properties().getOrDefault("taskKey", "unknown");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(orchestratorUrl + "/internal/results"))
                .header("Content-Type", "application/json")
                .header("X-Task-Key", taskKey)
                .header("X-Plan-Id", envelope.properties().getOrDefault("planId", ""))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(envelope.body()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 202 || response.statusCode() == 200) {
                log.info("Result callback sent for task '{}' to {} (destination={})",
                        taskKey, orchestratorUrl, envelope.destination());
            } else {
                log.error("Result callback for task '{}' returned HTTP {}: {}",
                        taskKey, response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending result callback for task '{}'", taskKey);
        } catch (Exception e) {
            log.error("Failed to send result callback for task '{}': {}", taskKey, e.getMessage(), e);
        }
    }
}
