package com.agentframework.orchestrator.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Executes individual retry operations in independent transactions.
 *
 * <p>Used by {@link AutoRetryScheduler} to isolate each retry so that a failure
 * in one item does not roll back the retry of other items in the same batch.</p>
 *
 * <p>The key is {@code propagation = REQUIRES_NEW}: each call starts a fresh
 * transaction, independent of the caller's transaction context.</p>
 */
@Service
public class RetryTransactionService {

    private static final Logger log = LoggerFactory.getLogger(RetryTransactionService.class);

    private final OrchestrationService orchestrationService;

    public RetryTransactionService(OrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retryItem(UUID itemId) {
        orchestrationService.retryFailedItem(itemId);
    }
}
