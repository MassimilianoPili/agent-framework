package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for {@link CriticalityProperties} (#56).
 *
 * <p>Ensures the {@code criticality.*} properties are bound even when no
 * other component explicitly enables them.</p>
 */
@Configuration
@EnableConfigurationProperties(CriticalityProperties.class)
public class CriticalityAutoConfiguration {
}
