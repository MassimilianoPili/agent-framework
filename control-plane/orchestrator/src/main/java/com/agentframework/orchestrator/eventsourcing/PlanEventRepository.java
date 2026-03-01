package com.agentframework.orchestrator.eventsourcing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlanEventRepository extends JpaRepository<PlanEvent, UUID> {

    /** Returns all events for a plan, ordered by sequence number (for SSE replay). */
    List<PlanEvent> findByPlanIdOrderBySequenceNumberAsc(UUID planId);

    /** Atomically computes the next sequence number using MAX (gap-safe, unlike COUNT). */
    @Query(value = "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM plan_event WHERE plan_id = :planId",
           nativeQuery = true)
    long nextSequence(@Param("planId") UUID planId);

    long countByPlanId(UUID planId);
}
