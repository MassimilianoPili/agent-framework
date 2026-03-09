package com.agentframework.orchestrator.analytics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container for repeatable {@link AllowedViolation} annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AllowedViolations {
    AllowedViolation[] value();
}
