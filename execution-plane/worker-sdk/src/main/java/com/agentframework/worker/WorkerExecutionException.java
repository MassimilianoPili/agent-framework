package com.agentframework.worker;

/**
 * Thrown when a worker encounters an unrecoverable error during task execution.
 * AbstractWorker.process() catches this and publishes a failure AgentResult.
 */
public class WorkerExecutionException extends RuntimeException {

    public WorkerExecutionException(String message) {
        super(message);
    }

    public WorkerExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
