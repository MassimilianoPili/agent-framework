package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.PlanOutcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link PlanOutcome} — GP training data for Council Taste Profile.
 */
@Repository
public interface PlanOutcomeRepository extends JpaRepository<PlanOutcome, UUID> {

    /**
     * Returns completed plan outcomes ordered by recency (most recent first).
     * Used by {@link com.agentframework.orchestrator.gp.PlanDecompositionPredictor}
     * to build the GP training set. Pageable limits training points (e.g. 200 max).
     */
    @Query("SELECT p FROM PlanOutcome p WHERE p.actualReward IS NOT NULL ORDER BY p.createdAt DESC")
    List<PlanOutcome> findTrainingData(Pageable pageable);
}
