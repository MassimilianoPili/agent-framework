package com.agentframework.orchestrator.reward;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PreferencePairRepository extends JpaRepository<PreferencePair, UUID> {

    List<PreferencePair> findByPlanId(UUID planId);

    /** Returns pairs with delta above threshold, sorted by delta descending, capped at limit. */
    @Query("SELECT p FROM PreferencePair p WHERE p.deltaReward >= :minDelta ORDER BY p.deltaReward DESC LIMIT :limit")
    List<PreferencePair> findByMinDelta(@Param("minDelta") float minDelta, @Param("limit") int limit);

    /** Checks if a pair already exists for a given task to avoid duplicates. */
    boolean existsByTaskKeyAndGenerationSource(String taskKey, String generationSource);
}
