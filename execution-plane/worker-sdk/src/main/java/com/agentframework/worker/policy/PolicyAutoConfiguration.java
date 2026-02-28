package com.agentframework.worker.policy;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the policy enforcement layer.
 *
 * <p>Activated when {@code agent.worker.policy.enabled=true} (default).
 * Registers {@link PathOwnershipEnforcer} and {@link ToolAuditLogger}
 * as beans for use by the {@code PolicyEnforcingToolCallback} decorator.</p>
 *
 * <p>Disabled via {@code agent.worker.policy.enabled=false} for tests
 * or environments where enforcement is not desired.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "agent.worker.policy.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PolicyProperties.class)
public class PolicyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PathOwnershipEnforcer pathOwnershipEnforcer(PolicyProperties properties) {
        return new PathOwnershipEnforcer(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolAuditLogger toolAuditLogger(PolicyProperties properties) {
        return new ToolAuditLogger(properties);
    }
}
