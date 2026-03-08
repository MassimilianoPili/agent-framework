package com.agentframework.orchestrator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StaleDetectorProperties.class)
public class StaleDetectorAutoConfiguration {
}
