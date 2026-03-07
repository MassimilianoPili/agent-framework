package com.agentframework.orchestrator.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Executes individual redispatch operations in independent transactions.
 *
 * <p>Used by {@link RedispatchPollerService} to isolate each redispatch so that a failure
 * in one item does not roll back the redispatch of other items in the same batch.</p>
 *
 * <p>The key is {@code propagation = REQUIRES_NEW}: each call starts a fresh
 * transaction, independent of the caller's transaction context.</p>
 *
 * @see RetryTransactionService for the equivalent pattern used by auto-retry
 */
@Service
public class RedispatchTransactionService {

    private static final Logger log = LoggerFactory.getLogger(RedispatchTransactionService.class);

    private final OrchestrationService orchestrationService;

    public RedispatchTransactionService(OrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void redispatchItem(UUID itemId) {
        orchestrationService.redispatchItem(itemId);
    }
}
