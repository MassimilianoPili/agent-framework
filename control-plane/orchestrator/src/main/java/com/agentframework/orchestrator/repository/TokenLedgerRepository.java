package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.budget.TokenLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the double-entry token ledger (#33).
 */
public interface TokenLedgerRepository extends JpaRepository<TokenLedger, UUID> {

    List<TokenLedger> findByPlanIdOrderByCreatedAtAsc(UUID planId);

    @Query("SELECT t.balanceAfter FROM TokenLedger t " +
           "WHERE t.planId = :planId ORDER BY t.createdAt DESC LIMIT 1")
    Optional<Long> findLatestBalance(@Param("planId") UUID planId);

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM token_ledger " +
           "WHERE plan_id = :planId AND entry_type = 'DEBIT'", nativeQuery = true)
    long sumDebits(@Param("planId") UUID planId);

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM token_ledger " +
           "WHERE plan_id = :planId AND entry_type = 'CREDIT'", nativeQuery = true)
    long sumCredits(@Param("planId") UUID planId);

    // ── Per-workerType breakdown (#33 observability) ─────────────────────────

    @Query(value = "SELECT worker_type, COALESCE(SUM(amount), 0) FROM token_ledger " +
           "WHERE plan_id = :planId AND entry_type = 'DEBIT' GROUP BY worker_type",
           nativeQuery = true)
    List<Object[]> sumDebitsByWorkerType(@Param("planId") UUID planId);

    @Query(value = "SELECT worker_type, COALESCE(SUM(amount), 0) FROM token_ledger " +
           "WHERE plan_id = :planId AND entry_type = 'CREDIT' GROUP BY worker_type",
           nativeQuery = true)
    List<Object[]> sumCreditsByWorkerType(@Param("planId") UUID planId);

    @Query(value = "SELECT COUNT(*) FROM token_ledger " +
           "WHERE plan_id = :planId AND entry_type = 'DEBIT'", nativeQuery = true)
    long countDebits(@Param("planId") UUID planId);
}
