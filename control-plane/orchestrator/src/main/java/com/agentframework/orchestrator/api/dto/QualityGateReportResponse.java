package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.QualityGateReport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record QualityGateReportResponse(
    UUID id,
    UUID planId,
    boolean passed,
    String summary,
    List<String> findings,
    Object metrics,
    Instant generatedAt
) {

    public static QualityGateReportResponse from(QualityGateReport report) {
        return new QualityGateReportResponse(
            report.getId(),
            report.getPlan().getId(),
            report.isPassed(),
            report.getSummary(),
            report.getFindings(),
            null,  // metrics: reserved for future use
            report.getGeneratedAt()
        );
    }
}
