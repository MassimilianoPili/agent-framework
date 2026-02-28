package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.DispatchAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchAttemptRepository extends JpaRepository<DispatchAttempt, UUID> {

    List<DispatchAttempt> findByItemIdOrderByAttemptNumberAsc(UUID itemId);

    @Query("SELECT MAX(a.attemptNumber) FROM DispatchAttempt a WHERE a.item.id = :itemId")
    Optional<Integer> findMaxAttemptNumber(UUID itemId);

    @Query("SELECT a FROM DispatchAttempt a WHERE a.item.id = :itemId AND a.completedAt IS NULL")
    Optional<DispatchAttempt> findOpenAttempt(UUID itemId);
}
