package com.agentframework.worker.sandbox;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration for the execution sandbox.
 *
 * <p>Only activated when {@code agent.worker.sandbox.enabled=true}.
 * Registers {@link SandboxProperties}, {@link SandboxExecutor}, and
 * {@link com.agentframework.worker.interceptor.SandboxBuildInterceptor}.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "agent.worker.sandbox.enabled", havingValue = "true")
@EnableConfigurationProperties(SandboxProperties.class)
@ComponentScan(basePackageClasses = SandboxAutoConfiguration.class)
public class SandboxAutoConfiguration {
}
