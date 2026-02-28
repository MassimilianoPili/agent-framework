package com.agentframework.workers.advisory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for the Advisory Worker.
 *
 * <p>Handles both {@code MANAGER} and {@code SPECIALIST} task types dispatched
 * via the {@code agent-advisory} topic. Workers are read-only: they receive
 * Glob, Grep, and Read tool access only — no Write, Edit, or Bash.</p>
 *
 * <p>The active system prompt is selected dynamically from the task's
 * {@code workerProfile} field (e.g. {@code be-manager} → {@code prompts/council/managers/be-manager.agent.md}).</p>
 */
@SpringBootApplication
public class AdvisoryWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdvisoryWorkerApplication.class, args);
    }
}
