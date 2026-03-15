package com.agentframework.orchestrator.integration;

import com.agentframework.orchestrator.integration.ExternalSystemAdapter.IntegrationEvent;
import com.agentframework.orchestrator.integration.ExternalSystemAdapter.IntegrationEvent.Direction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * External System Integration Hub.
 *
 * <p>Routes integration events to registered adapters with inline circuit breaker
 * protection. Manages adapter registration, event dispatch, and failure tracking.</p>
 *
 * <p>Circuit breaker states per adapter:</p>
 * <ul>
 *   <li><b>CLOSED</b>: normal operation, failures counted</li>
 *   <li><b>OPEN</b>: all calls skipped, cooldown timer active</li>
 *   <li><b>HALF_OPEN</b>: single probe call allowed; success → CLOSED, failure → OPEN</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "integration-hub", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(IntegrationHubConfig.class)
public class IntegrationHubService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationHubService.class);

    private final IntegrationHubConfig config;
    private final Map<String, ExternalSystemAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, CircuitState> circuitStates = new ConcurrentHashMap<>();
    private final Counter eventsDispatched;
    private final Counter eventsFailed;

    public IntegrationHubService(IntegrationHubConfig config,
                                  List<ExternalSystemAdapter> adapterList,
                                  @Nullable MeterRegistry meterRegistry) {
        this.config = config;
        for (ExternalSystemAdapter adapter : adapterList) {
            adapters.put(adapter.systemType(), adapter);
            circuitStates.put(adapter.systemType(), new CircuitState());
        }

        if (meterRegistry != null) {
            eventsDispatched = Counter.builder("integration_hub_events_dispatched_total")
                    .description("Total integration events dispatched")
                    .register(meterRegistry);
            eventsFailed = Counter.builder("integration_hub_events_failed_total")
                    .description("Total integration event delivery failures")
                    .register(meterRegistry);
        } else {
            eventsDispatched = null;
            eventsFailed = null;
        }

        log.info("Integration Hub initialized with {} adapters: {}",
                adapters.size(), adapters.keySet());
    }

    /**
     * Dispatches an outbound event to the target system adapter.
     *
     * @param event the event to dispatch
     * @return external reference from the adapter, or null
     */
    @Nullable
    public String dispatch(IntegrationEvent event) {
        ExternalSystemAdapter adapter = adapters.get(event.systemType());
        if (adapter == null) {
            log.warn("No adapter registered for system type: {}", event.systemType());
            return null;
        }

        CircuitState circuit = circuitStates.get(event.systemType());
        if (circuit != null && !circuit.allowRequest()) {
            log.debug("Circuit OPEN for {}, skipping event {}", event.systemType(), event.id());
            return null;
        }

        try {
            String ref = adapter.pushEvent(event);
            if (circuit != null) circuit.recordSuccess();
            if (eventsDispatched != null) eventsDispatched.increment();
            log.debug("Dispatched event {} to {} (ref={})", event.id(), event.systemType(), ref);
            return ref;
        } catch (Exception e) {
            if (circuit != null) circuit.recordFailure(config.adapter().circuitBreakerThreshold());
            if (eventsFailed != null) eventsFailed.increment();
            log.error("Failed to dispatch event {} to {}: {}", event.id(), event.systemType(), e.getMessage());
            return null;
        }
    }

    /**
     * Processes an inbound webhook through the appropriate adapter.
     *
     * @param systemType target system type
     * @param headers    HTTP headers
     * @param payload    raw JSON body
     * @return parsed integration event, or null
     */
    @Nullable
    public IntegrationEvent processInbound(String systemType, Map<String, String> headers, String payload) {
        ExternalSystemAdapter adapter = adapters.get(systemType);
        if (adapter == null || !adapter.supportsWebhooks()) {
            log.warn("No webhook-capable adapter for system type: {}", systemType);
            return null;
        }

        return adapter.processWebhook(headers, payload);
    }

    /**
     * Pulls status from an external system.
     *
     * @param systemType   system type
     * @param externalRef  external reference
     * @return status map, or null
     */
    @Nullable
    public Map<String, Object> pullStatus(String systemType, String externalRef) {
        ExternalSystemAdapter adapter = adapters.get(systemType);
        if (adapter == null) return null;
        return adapter.pullStatus(externalRef);
    }

    /**
     * Creates an outbound integration event.
     */
    public IntegrationEvent createOutboundEvent(String systemType, String eventType,
                                                  String payload, @Nullable UUID planId) {
        return new IntegrationEvent(UUID.randomUUID(), Direction.OUTBOUND,
                systemType, eventType, payload, planId, null);
    }

    /**
     * Returns registered adapter system types.
     */
    public Set<String> registeredSystems() {
        return Collections.unmodifiableSet(adapters.keySet());
    }

    /**
     * Returns circuit breaker status for monitoring.
     */
    public Map<String, String> circuitBreakerStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        circuitStates.forEach((system, state) -> status.put(system, state.state().name()));
        return status;
    }

    // --- Inline Circuit Breaker ---

    static class CircuitState {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private volatile State state = State.CLOSED;
        private int consecutiveFailures = 0;
        private long openedAt = 0;
        private static final long DEFAULT_COOLDOWN_MS = 60_000;

        synchronized boolean allowRequest() {
            if (state == State.CLOSED) return true;
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - openedAt >= DEFAULT_COOLDOWN_MS) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            }
            // HALF_OPEN: allow one probe
            return true;
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            state = State.CLOSED;
        }

        synchronized void recordFailure(int threshold) {
            consecutiveFailures++;
            if (state == State.HALF_OPEN || consecutiveFailures >= threshold) {
                state = State.OPEN;
                openedAt = System.currentTimeMillis();
            }
        }

        State state() { return state; }
    }
}
