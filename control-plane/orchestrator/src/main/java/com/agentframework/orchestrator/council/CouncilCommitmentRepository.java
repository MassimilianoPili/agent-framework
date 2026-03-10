package com.agentframework.orchestrator.council;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for council commitment audit records (#46).
 */
public interface CouncilCommitmentRepository extends JpaRepository<CouncilCommitment, UUID> {

    List<CouncilCommitment> findByPlanIdOrderByCommittedAtAsc(UUID planId);
}
