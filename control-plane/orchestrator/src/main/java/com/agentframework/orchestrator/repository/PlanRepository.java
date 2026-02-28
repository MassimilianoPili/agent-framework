package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanRepository extends JpaRepository<Plan, UUID> {

    List<Plan> findByStatus(PlanStatus status);
}
