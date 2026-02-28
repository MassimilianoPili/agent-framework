package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.PlanTokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanTokenUsageRepository extends JpaRepository<PlanTokenUsage, UUID> {

    Optional<PlanTokenUsage> findByPlanIdAndWorkerType(UUID planId, String workerType);

    List<PlanTokenUsage> findByPlanId(UUID planId);

    /**
     * Atomically increments the token counter for a (plan, workerType) row.
     * Returns the number of rows updated (0 if no row exists yet).
     *
     * Callers must ensure the row exists before calling this (via findOrCreate in the service).
     * The UPDATE acquires an exclusive row lock, so concurrent calls are safe.
     */
    @Modifying
    @Query("UPDATE PlanTokenUsage u SET u.tokensUsed = u.tokensUsed + :delta " +
           "WHERE u.planId = :planId AND u.workerType = :workerType")
    int incrementTokensUsed(@Param("planId") UUID planId,
                            @Param("workerType") String workerType,
                            @Param("delta") long delta);
}
