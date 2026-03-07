package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.CalibrationAudit.CalibrationReport;
import com.agentframework.orchestrator.event.SpringPlanEvent;
import com.agentframework.orchestrator.gp.TaskOutcomeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Periodic calibration audit for GP prediction quality.
 *
 * <p>Loads GP predictions (gp_mu) and actual outcomes (actual_reward) from
 * task_outcomes, treats gp_mu as the predicted probability of success
 * (actual_reward &gt; 0.5), and computes calibration metrics.</p>
 *
 * <p>When a Dutch Book vulnerability is detected (MCE &gt; threshold),
 * publishes a {@link SpringPlanEvent#CALIBRATION_DRIFT} event for SSE listeners.</p>
 *
 * <p>Results are cached in-memory (volatile for thread safety) and exposed
 * via the REST endpoint for dashboard consumption.</p>
 *
 * @see CalibrationAudit
 * @see <a href="https://doi.org/10.1111/j.2517-6161.1983.tb01232.x">
 *     DeGroot &amp; Fienberg (1983), The Comparison and Evaluation of Forecasters</a>
 */
@Service
@ConditionalOnProperty(prefix = "calibration", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CalibrationAuditService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationAuditService.class);

    static final int MAX_PREDICTIONS = 1000;
    static final int MIN_PREDICTIONS = 20;

    private final TaskOutcomeRepository taskOutcomeRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${calibration.num-bins:10}")
    private int numBins;

    @Value("${calibration.dutch-book-threshold:0.15}")
    private double dutchBookThreshold;

    private volatile CalibrationReport latestReport;

    public CalibrationAuditService(TaskOutcomeRepository taskOutcomeRepository,
                                    ApplicationEventPublisher eventPublisher) {
        this.taskOutcomeRepository = taskOutcomeRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Runs a global calibration audit across all worker types.
     *
     * <ol>
     *   <li>Load up to {@link #MAX_PREDICTIONS} outcomes with gp_mu and actual_reward</li>
     *   <li>Convert: predicted = gp_mu, actual = (actual_reward &gt; 0.5)</li>
     *   <li>Compute ECE, Brier Score, MCE, Dutch Book vulnerability</li>
     *   <li>If Dutch Book detected → publish {@link SpringPlanEvent#CALIBRATION_DRIFT}</li>
     *   <li>Cache result for REST endpoint</li>
     * </ol>
     *
     * @return calibration report, or null if insufficient data
     */
    public CalibrationReport auditAll() {
        List<Object[]> data = taskOutcomeRepository.findCalibrationData(MAX_PREDICTIONS);

        if (data.size() < MIN_PREDICTIONS) {
            log.debug("Calibration audit skipped: only {} predictions (min={})", data.size(), MIN_PREDICTIONS);
            return null;
        }

        double[] predicted = new double[data.size()];
        boolean[] actual = new boolean[data.size()];

        for (int i = 0; i < data.size(); i++) {
            predicted[i] = ((Number) data.get(i)[0]).doubleValue(); // gp_mu
            double reward = ((Number) data.get(i)[1]).doubleValue(); // actual_reward
            actual[i] = reward > 0.5;
        }

        CalibrationReport report = CalibrationAudit.audit(predicted, actual, numBins, dutchBookThreshold);
        this.latestReport = report;

        if (report.dutchBookVulnerable()) {
            log.warn("Calibration drift detected: ECE={}, MCE={}, Brier={}",
                    String.format("%.4f", report.ece()),
                    String.format("%.4f", report.maxCalibrationError()),
                    String.format("%.4f", report.brierScore()));
            eventPublisher.publishEvent(SpringPlanEvent.forSystem(SpringPlanEvent.CALIBRATION_DRIFT));
        } else {
            log.debug("Calibration audit OK: ECE={}, MCE={}, Brier={}, predictions={}",
                    String.format("%.4f", report.ece()),
                    String.format("%.4f", report.maxCalibrationError()),
                    String.format("%.4f", report.brierScore()),
                    report.totalPredictions());
        }

        return report;
    }

    /**
     * Runs a calibration audit filtered by worker type.
     *
     * @param workerType worker type name
     * @return calibration report, or null if insufficient data
     */
    public CalibrationReport auditByWorkerType(String workerType) {
        List<Object[]> data = taskOutcomeRepository.findCalibrationDataByWorkerType(
                workerType, MAX_PREDICTIONS);

        if (data.size() < MIN_PREDICTIONS) {
            return null;
        }

        double[] predicted = new double[data.size()];
        boolean[] actual = new boolean[data.size()];

        for (int i = 0; i < data.size(); i++) {
            predicted[i] = ((Number) data.get(i)[0]).doubleValue();
            double reward = ((Number) data.get(i)[1]).doubleValue();
            actual[i] = reward > 0.5;
        }

        return CalibrationAudit.audit(predicted, actual, numBins, dutchBookThreshold);
    }

    /** Returns the latest cached calibration report (for REST endpoint). */
    public CalibrationReport getLatestReport() {
        return latestReport;
    }

    @Scheduled(fixedDelayString = "${calibration.check-interval-ms:3600000}")
    void scheduledAudit() {
        auditAll();
    }
}
