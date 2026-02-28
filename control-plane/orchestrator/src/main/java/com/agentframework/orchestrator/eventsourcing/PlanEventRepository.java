package com.agentframework.orchestrator.eventsourcing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanEventRepository extends JpaRepository<PlanEvent, UUID> {

    /** Returns all events for a plan, ordered by sequence number (for SSE replay). */
    List<PlanEvent> findByPlanIdOrderBySequenceNumberAsc(UUID planId);

    /** Used to compute the next sequence number (countByPlanId + 1). */
    long countByPlanId(UUID planId);
}
