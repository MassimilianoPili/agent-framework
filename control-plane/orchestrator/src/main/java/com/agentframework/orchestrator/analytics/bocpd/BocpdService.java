package com.agentframework.orchestrator.analytics.bocpd;

import com.agentframework.orchestrator.analytics.SliDefinitionService;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestration service for BOCPD-based changepoint detection on SLI streams.
 *
 * <p>Periodically polls SLI values from {@link SliDefinitionService}, feeds them
 * to per-stream {@link BocpdMultiscaleAggregator} instances, and publishes
 * {@link SpringPlanEvent} when a confirmed changepoint is detected.</p>
 *
 * <p>Each worker type produces a set of SLIs (availability, latency, quality,
 * throughput). Each SLI gets its own multiscale BOCPD detector. Changepoints
 * are published as {@code CHANGEPOINT_DETECTED} events for SSE broadcast
 * and adaptive response by the orchestrator.</p>
 *
 * @see BocpdDetector
 * @see BocpdMultiscaleAggregator
 * @see SliDefinitionService
 */
@Service
@ConditionalOnProperty(prefix = "agent-framework.bocpd", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(BocpdConfig.class)
public class BocpdService {

    private static final Logger log = LoggerFactory.getLogger(BocpdService.class);

    /** Event type for changepoint detection (added to SpringPlanEvent). */
    public static final String CHANGEPOINT_DETECTED = "CHANGEPOINT_DETECTED";

    /** SLI names to monitor. */
    private static final List<String> MONITORED_SLIS = List.of(
            "availability", "latency_p95_seconds", "quality"
    );

    private final SliDefinitionService sliService;
    private final ApplicationEventPublisher eventPublisher;
    private final BocpdConfig config;

    /** Per-stream multiscale aggregators: key = "workerType:sliName" */
    private final Map<String, BocpdMultiscaleAggregator> aggregators = new ConcurrentHashMap<>();

    /** Recent changepoints for REST endpoint exposure. */
    private volatile List<DetectedChangepoint> recentChangepoints = List.of();

    public BocpdService(SliDefinitionService sliService,
                        ApplicationEventPublisher eventPublisher,
                        BocpdConfig config) {
        this.sliService = sliService;
        this.eventPublisher = eventPublisher;
        this.config = config;
    }

    /**
     * Scheduled polling of SLI values across all known worker types.
     *
     * <p>For each worker type and each monitored SLI, feeds the current value
     * to the corresponding BOCPD detector and checks for changepoints.</p>
     */
    @Scheduled(fixedDelayString = "${agent-framework.bocpd.poll-interval-ms:30000}")
    public void pollAndDetect() {
        List<String> workerTypes = List.of("BE", "FE", "REVIEW", "CONTEXT_MANAGER", "CONTRACT");
        Instant now = Instant.now();
        List<DetectedChangepoint> detected = new ArrayList<>();

        for (String workerType : workerTypes) {
            try {
                SliDefinitionService.SliReport report = sliService.computeSlis(workerType);
                if (report.totalItems() == 0) continue;

                for (String sliName : MONITORED_SLIS) {
                    SliDefinitionService.SliValue sli = report.findByName(sliName);
                    if (sli == null) continue;

                    String streamKey = workerType + ":" + sliName;
                    BocpdMultiscaleAggregator aggregator = aggregators.computeIfAbsent(
                            streamKey, k -> new BocpdMultiscaleAggregator(streamKey, config));

                    BocpdMultiscaleAggregator.MultiscaleResult result =
                            aggregator.observe(sli.value(), now);

                    if (result.confirmed()) {
                        DetectedChangepoint cp = new DetectedChangepoint(
                                workerType, sliName, sli.value(), now,
                                result.votesFor(), result.totalScales());
                        detected.add(cp);

                        log.warn("BOCPD changepoint confirmed: {} {} value={} votes={}/{}",
                                workerType, sliName,
                                String.format("%.4f", sli.value()),
                                result.votesFor(), result.totalScales());

                        String extraJson = String.format(
                                "{\"workerType\":\"%s\",\"sli\":\"%s\",\"value\":%.4f,\"votes\":%d,\"scales\":%d}",
                                workerType, sliName, sli.value(),
                                result.votesFor(), result.totalScales());
                        eventPublisher.publishEvent(
                                SpringPlanEvent.forSystem(CHANGEPOINT_DETECTED, extraJson));
                    }
                }
            } catch (Exception e) {
                log.debug("BOCPD poll failed for workerType {}: {}", workerType, e.getMessage());
            }
        }

        if (!detected.isEmpty()) {
            this.recentChangepoints = List.copyOf(detected);
        }

        log.debug("BOCPD poll completed: {} streams monitored, {} changepoints detected",
                aggregators.size(), detected.size());
    }

    /** Returns the most recently detected changepoints (for REST endpoint). */
    public List<DetectedChangepoint> getRecentChangepoints() {
        return recentChangepoints;
    }

    /** Returns the number of active streams being monitored. */
    public int activeStreamCount() {
        return aggregators.size();
    }

    /**
     * A detected and confirmed changepoint.
     *
     * @param workerType  the worker type whose SLI changed
     * @param sliName     the SLI that triggered detection
     * @param currentValue the SLI value at detection time
     * @param detectedAt  when the changepoint was detected
     * @param votesFor    how many scales confirmed the changepoint
     * @param totalScales total number of scales in the aggregator
     */
    public record DetectedChangepoint(
            String workerType,
            String sliName,
            double currentValue,
            Instant detectedAt,
            int votesFor,
            int totalScales
    ) {}
}
