package com.agentframework.worker.claude;

import com.agentframework.worker.ToolAllowlist;
import com.agentframework.worker.policy.PathOwnershipEnforcer;
import com.agentframework.worker.policy.PolicyProperties;
import com.agentframework.worker.policy.ToolAuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for #20 — WorkerChatClientFactory.create() overload with optional model ID.
 *
 * Verifies that the factory correctly handles the three-argument create() method
 * with and without a modelId override.
 */
@ExtendWith(MockitoExtension.class)
class WorkerChatClientFactoryModelOverrideTest {

    @Mock
    ChatModel chatModel;

    @Mock
    ObjectProvider<PathOwnershipEnforcer> enforcerProvider;

    @Mock
    ObjectProvider<ToolAuditLogger> auditLoggerProvider;

    @Mock
    ObjectProvider<PolicyProperties> policyPropertiesProvider;

    private WorkerChatClientFactory factory;

    @BeforeEach
    void setUp() {
        // All optional providers return null → policy inactive, no tool enforcement
        when(enforcerProvider.getIfAvailable()).thenReturn(null);
        when(auditLoggerProvider.getIfAvailable()).thenReturn(null);
        when(policyPropertiesProvider.getIfAvailable()).thenReturn(null);

        factory = new WorkerChatClientFactory(
            chatModel,
            List.of(),                // no tool providers in unit test
            enforcerProvider,
            auditLoggerProvider,
            policyPropertiesProvider
        );
    }

    @Test
    void create_withModelId_buildsClientSuccessfully() {
        ChatClient client = factory.create("BE", ToolAllowlist.ALL, "claude-haiku-4-5-20251001");

        assertThat(client).isNotNull();
    }

    @Test
    void create_withNullModelId_buildsDefaultClient() {
        ChatClient client = factory.create("BE", ToolAllowlist.ALL, null);

        assertThat(client).isNotNull();
    }

    @Test
    void create_withBlankModelId_buildsDefaultClient() {
        // Blank strings are treated as "no override" — same as null
        ChatClient client = factory.create("BE", ToolAllowlist.ALL, "   ");

        assertThat(client).isNotNull();
    }
}
