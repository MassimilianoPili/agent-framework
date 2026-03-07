package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.ItemStatus;
import com.agentframework.orchestrator.domain.PlanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanItemRepository extends JpaRepository<PlanItem, UUID> {

    /**
     * Loads a PlanItem with its Plan eagerly fetched in a single query.
     * Use this instead of {@code findById()} when the caller needs {@code item.getPlan()}.
     */
    @Query("SELECT i FROM PlanItem i JOIN FETCH i.plan WHERE i.id = :id")
    Optional<PlanItem> findByIdWithPlan(@Param("id") UUID id);

    List<PlanItem> findByPlanIdAndStatus(UUID planId, ItemStatus status);

    List<PlanItem> findByPlanId(UUID planId);

    /**
     * Items that are still in-flight (not terminal).
     */
    @Query("SELECT i FROM PlanItem i WHERE i.plan.id = :planId AND i.status NOT IN (com.agentframework.orchestrator.domain.ItemStatus.DONE, com.agentframework.orchestrator.domain.ItemStatus.FAILED)")
    List<PlanItem> findActiveByPlanId(@Param("planId") UUID planId);

    /**
     * Items that are WAITING and whose all dependencies are DONE.
     * This is the core dependency-resolution query: it finds items ready to be dispatched.
     *
     * An item is dispatchable when:
     * 1. Its status is WAITING, AND
     * 2. It has no dependencies (dependsOn is empty), OR
     * 3. All its dependencies (by taskKey) have status DONE
     */
    @Query("""
        SELECT i FROM PlanItem i JOIN FETCH i.plan
        WHERE i.plan.id = :planId
          AND i.status = com.agentframework.orchestrator.domain.ItemStatus.WAITING
          AND NOT EXISTS (
              SELECT dep FROM PlanItem dep
              WHERE dep.plan.id = :planId
                AND dep.taskKey IN (SELECT d FROM PlanItem i2 JOIN i2.dependsOn d WHERE i2.id = i.id)
                AND dep.status != com.agentframework.orchestrator.domain.ItemStatus.DONE
          )
        """)
    List<PlanItem> findDispatchableItems(@Param("planId") UUID planId);

    /**
     * Finds FAILED items whose nextRetryAt is in the past (eligible for automatic retry).
     * The plan must be RUNNING or PAUSED (not COMPLETED or permanently FAILED).
     */
    @Query("""
        SELECT i FROM PlanItem i JOIN FETCH i.plan
        WHERE i.status = com.agentframework.orchestrator.domain.ItemStatus.FAILED
          AND i.nextRetryAt IS NOT NULL
          AND i.nextRetryAt <= :now
          AND i.plan.status IN (
              com.agentframework.orchestrator.domain.PlanStatus.RUNNING,
              com.agentframework.orchestrator.domain.PlanStatus.PAUSED
          )
        """)
    List<PlanItem> findRetryEligible(@Param("now") Instant now);

    /**
     * Finds DISPATCHED items whose dispatchedAt is older than the given cutoff.
     * These are stale tasks — workers that never reported back.
     * The plan must still be active (RUNNING or PAUSED).
     */
    @Query("""
        SELECT i FROM PlanItem i JOIN FETCH i.plan
        WHERE i.status = com.agentframework.orchestrator.domain.ItemStatus.DISPATCHED
          AND i.dispatchedAt IS NOT NULL
          AND i.dispatchedAt <= :cutoff
          AND i.plan.status IN (
              com.agentframework.orchestrator.domain.PlanStatus.RUNNING,
              com.agentframework.orchestrator.domain.PlanStatus.PAUSED
          )
        """)
    List<PlanItem> findStaleDispatched(@Param("cutoff") Instant cutoff);

    // ── Aggregate queries for CriticalityMonitor (sandpile model) ────────

    /** Counts WAITING items grouped by WorkerType. Returns Object[]{WorkerType, Long}. */
    @Query("SELECT i.workerType, COUNT(i) FROM PlanItem i "
         + "WHERE i.status = com.agentframework.orchestrator.domain.ItemStatus.WAITING "
         + "GROUP BY i.workerType")
    List<Object[]> countPendingByWorkerType();

    /** Counts FAILED items grouped by WorkerType. Returns Object[]{WorkerType, Long}. */
    @Query("SELECT i.workerType, COUNT(i) FROM PlanItem i "
         + "WHERE i.status = com.agentframework.orchestrator.domain.ItemStatus.FAILED "
         + "GROUP BY i.workerType")
    List<Object[]> countFailedByWorkerType();

    /** Counts stale DISPATCHED items (dispatched before cutoff) grouped by WorkerType. */
    @Query("SELECT i.workerType, COUNT(i) FROM PlanItem i "
         + "WHERE i.status = com.agentframework.orchestrator.domain.ItemStatus.DISPATCHED "
         + "AND i.dispatchedAt <= :cutoff "
         + "GROUP BY i.workerType")
    List<Object[]> countStaleDispatchedByWorkerType(@Param("cutoff") Instant cutoff);
}
