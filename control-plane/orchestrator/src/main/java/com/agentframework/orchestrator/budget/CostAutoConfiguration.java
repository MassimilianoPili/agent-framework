package com.agentframework.orchestrator.budget;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CostProperties.class)
public class CostAutoConfiguration {
}
