package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    List<Plan> findByStatus(PlanStatus status);

    List<Plan> findAllByOrderByCreatedAtDesc();

    List<Plan> findByStatusOrderByCreatedAtDesc(PlanStatus status);

    @Query("SELECT p FROM Plan p WHERE p.workspaceVolume IS NOT NULL " +
           "AND p.status IN (com.agentframework.orchestrator.domain.PlanStatus.COMPLETED, " +
           "com.agentframework.orchestrator.domain.PlanStatus.FAILED) " +
           "AND p.completedAt < :cutoff")
    List<Plan> findPlansWithStaleWorkspaces(@Param("cutoff") Instant cutoff);
}
