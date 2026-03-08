package com.agentframework.worker.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone Spring Boot application for hosting heavy workers in a separate JVM.
 *
 * <p>The worker-runtime receives tasks via REST ({@code POST /tasks}), processes them
 * using discovered {@code AbstractWorker} beans, and sends results back to the
 * orchestrator via HTTP callback ({@code POST /internal/results}).
 *
 * <p>Which worker beans are available depends on which worker JARs are on the classpath.
 * In Docker, each worker-runtime container includes worker JARs for a specific
 * {@code WorkerType} (e.g., all BE profiles: be-java, be-go, be-python, etc.).
 */
@SpringBootApplication
public class WorkerRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerRuntimeApplication.class, args);
    }
}
