package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.DispatchAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchAttemptRepository extends JpaRepository<DispatchAttempt, UUID> {

    List<DispatchAttempt> findByItemIdOrderByAttemptNumberAsc(UUID itemId);

    @Query("SELECT MAX(a.attemptNumber) FROM DispatchAttempt a WHERE a.item.id = :itemId")
    Optional<Integer> findMaxAttemptNumber(UUID itemId);

    @Query("SELECT a FROM DispatchAttempt a WHERE a.item.id = :itemId AND a.completedAt IS NULL ORDER BY a.attemptNumber DESC LIMIT 1")
    Optional<DispatchAttempt> findOpenAttempt(UUID itemId);

    /**
     * Bulk-closes all open DispatchAttempts for an item before creating a new one.
     * Called by {@code dispatchReadyItems()} and {@code redispatchItem()} to prevent
     * orphaned attempts from accumulating when a retry or redispatch is triggered.
     *
     * @param itemId the plan item whose open attempts should be closed
     * @param now    timestamp to set as completedAt
     * @param reason short label stored in failureReason (e.g. "closed-before-retry")
     * @return number of rows updated
     */
    @Modifying
    @Query("UPDATE DispatchAttempt a SET a.completedAt = :now, a.failureReason = :reason " +
           "WHERE a.item.id = :itemId AND a.completedAt IS NULL")
    int closeOrphanedAttempts(@Param("itemId") UUID itemId,
                              @Param("now") Instant now,
                              @Param("reason") String reason);
}
