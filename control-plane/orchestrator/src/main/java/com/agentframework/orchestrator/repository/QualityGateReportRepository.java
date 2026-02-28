package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.QualityGateReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface QualityGateReportRepository extends JpaRepository<QualityGateReport, UUID> {

    Optional<QualityGateReport> findByPlanId(UUID planId);
}
