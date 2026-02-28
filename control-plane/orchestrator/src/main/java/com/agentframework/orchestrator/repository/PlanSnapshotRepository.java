package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.PlanSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanSnapshotRepository extends JpaRepository<PlanSnapshot, UUID> {

    List<PlanSnapshot> findByPlanIdOrderByCreatedAtAsc(UUID planId);
}
