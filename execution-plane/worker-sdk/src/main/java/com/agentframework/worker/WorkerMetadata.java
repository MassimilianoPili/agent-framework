package com.agentframework.worker;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares static metadata for a worker, replacing the five method overrides
 * ({@code workerType}, {@code workerProfile}, {@code systemPromptFile},
 * {@code toolAllowlist}, {@code skillPaths}) that every generated worker
 * previously had to provide.
 *
 * <p>{@link AbstractWorker} reads this annotation via reflection and uses its
 * values as defaults. Method overrides still take precedence — hand-written
 * workers can combine annotations with selective overrides.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkerMetadata {

    /** Worker type identifier (e.g., "BE_JAVA", "MANAGER"). Must match WorkerType enum. */
    String workerType();

    /** Worker profile for message filtering (e.g., "be-java"). Empty string means null/untyped. */
    String workerProfile() default "";

    /** Classpath path of the system prompt file (e.g., "prompts/be-java.agent.md"). */
    String systemPromptFile();

    /** MCP tool allowlist. Empty array means ALL tools allowed. */
    String[] toolAllowlist() default {};

    /** Additional skill document paths to compose into the system prompt. */
    String[] skillPaths() default {};
}
