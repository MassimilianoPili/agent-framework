package com.agentframework.orchestrator.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * ChatClient for the orchestrator — used by PlannerService and QualityGateService.
     * No tools registered: the planner and reviewer are reasoning-only agents.
     * Uses claude-opus-4-6 with low temperature for deterministic plan decomposition.
     */
    @Bean
    public ChatClient plannerChatClient(AnthropicChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
