package com.agentframework.orchestrator.analytics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents an intentional violation of a formal property in the state machine (#38).
 *
 * <p>Some transitions violate intuitive properties (e.g., DONE → WAITING breaks
 * "DONE is terminal") but are bounded and intentional. This annotation makes
 * the violation explicit and machine-readable, so the verifier can distinguish
 * between bugs and design decisions.</p>
 *
 * <p>Example: {@code ItemStatus.DONE} allows DONE → WAITING for the ralph-loop
 * quality gate, bounded by {@code maxRalphLoops}.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Repeatable(AllowedViolations.class)
public @interface AllowedViolation {

    /** The property identifier being violated (e.g., "P4"). */
    String property();

    /** Human-readable reason for the violation. */
    String reason();

    /** What bounds this violation, preventing unbounded cycling (e.g., "maxRalphLoops=2"). */
    String boundedBy() default "";
}
