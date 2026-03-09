package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for token ledger observability (#33).
 *
 * <pre>
 * token-ledger:
 *   low-efficiency-threshold: 0.15
 * </pre>
 */
@ConfigurationProperties(prefix = "token-ledger")
public record TokenLedgerProperties(

    /**
     * Efficiency threshold below which a LOW_EFFICIENCY alert is emitted.
     * Only triggers after at least 3 debit entries (avoids noise during startup).
     */
    double lowEfficiencyThreshold

) {}
