package com.agentframework.orchestrator.council;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link CouncilProperties} binding from {@code council.*} in application.yml.
 */
@Configuration
@EnableConfigurationProperties(CouncilProperties.class)
public class CouncilAutoConfiguration {
}
