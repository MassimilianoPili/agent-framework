package com.agentframework.orchestrator.gp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository for serendipity file associations.
 *
 * <p>Stores file paths suggested by the Context Manager for tasks that had high GP residual.
 * Used at query time to find historically-surprising files for similar new tasks.</p>
 */
public interface ContextFileOutcomeRepository extends JpaRepository<ContextFileOutcome, UUID> {

    /**
     * Finds file outcomes associated with a set of task outcomes, filtered by minimum residual.
     * Used in the query path: after finding similar task_outcomes via HNSW, load their files.
     */
    @Query("SELECT c FROM ContextFileOutcome c " +
           "WHERE c.taskOutcomeId IN :outcomeIds AND c.residual >= :minResidual")
    List<ContextFileOutcome> findByOutcomeIdsAndMinResidual(
            @Param("outcomeIds") Collection<UUID> outcomeIds,
            @Param("minResidual") float minResidual);

    /**
     * Checks if file outcomes have already been collected for a task outcome.
     * Prevents duplicate collection on redelivered events.
     */
    boolean existsByTaskOutcomeId(UUID taskOutcomeId);
}
