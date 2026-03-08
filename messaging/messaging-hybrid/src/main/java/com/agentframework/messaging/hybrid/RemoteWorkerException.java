package com.agentframework.messaging.hybrid;

/**
 * Thrown when communication with a remote worker JVM fails.
 */
public class RemoteWorkerException extends RuntimeException {

    public RemoteWorkerException(String message) {
        super(message);
    }

    public RemoteWorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
