package com.agentframework.worker.interceptor;

import com.agentframework.common.sandbox.SandboxResult;
import com.agentframework.worker.context.AgentContext;
import com.agentframework.worker.dto.AgentTask;
import com.agentframework.worker.sandbox.SandboxExecutor;
import com.agentframework.worker.sandbox.SandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SandboxBuildInterceptor}.
 */
@ExtendWith(MockitoExtension.class)
class SandboxBuildInterceptorTest {

    @Mock private SandboxExecutor sandboxExecutor;

    private SandboxProperties properties;
    private ObjectMapper objectMapper;
    private SandboxBuildInterceptor interceptor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new SandboxProperties();
        properties.setEnabled(true);
        properties.setImagesByProfile(Map.of(
            "be-java", "agent-sandbox-java:21",
            "fe-react", "agent-sandbox-node:22"
        ));
        properties.setBuildCommands(Map.of(
            "be-java", "mvn compile -q -B",
            "fe-react", "npm install && npm run build"
        ));

        interceptor = new SandboxBuildInterceptor(sandboxExecutor, properties, objectMapper);
    }

    private AgentContext makeContext(String workspacePath) {
        return new AgentContext(
            UUID.randomUUID(), UUID.randomUUID(), "T1", "title", "desc",
            "spec", "system prompt", Map.of(), "", List.of(), null, null,
            workspacePath
        );
    }

    private AgentTask makeTask(String workerType, String workerProfile) {
        return new AgentTask(
            UUID.randomUUID(), UUID.randomUUID(), "T1", "title", "desc",
            workerType, workerProfile, null, null, 1, null, null, null,
            null, null, null, null, null, null, null
        );
    }

    @Test
    @DisplayName("afterExecute skips when workspace path is null")
    void afterExecute_noWorkspace_skips() {
        AgentContext ctx = makeContext(null);
        AgentTask task = makeTask("BE", "be-java");

        String result = interceptor.afterExecute(ctx, "{\"code\":\"ok\"}", task);

        assertThat(result).isEqualTo("{\"code\":\"ok\"}");
        verifyNoInteractions(sandboxExecutor);
    }

    @Test
    @DisplayName("afterExecute skips for non-domain worker types")
    void afterExecute_nonDomainWorker_skips() {
        AgentContext ctx = makeContext("/workspace/test123");
        AgentTask task = makeTask("REVIEW", null);

        String result = interceptor.afterExecute(ctx, "{}", task);

        assertThat(result).isEqualTo("{}");
        verifyNoInteractions(sandboxExecutor);
    }

    @Test
    @DisplayName("afterExecute skips when no sandbox image configured for profile")
    void afterExecute_noImageForProfile_skips() {
        AgentContext ctx = makeContext("/workspace/test123");
        AgentTask task = makeTask("BE", "be-rust"); // no image configured

        String result = interceptor.afterExecute(ctx, "{}", task);

        assertThat(result).isEqualTo("{}");
        verifyNoInteractions(sandboxExecutor);
    }

    @Test
    @DisplayName("afterExecute enriches result with build output on success")
    void afterExecute_successfulBuild_enrichesResult() throws Exception {
        AgentContext ctx = makeContext("/workspace/test123");
        AgentTask task = makeTask("BE", "be-java");

        when(sandboxExecutor.execute(any())).thenReturn(
            new SandboxResult(0, "BUILD SUCCESS", "", 5000, false)
        );

        String result = interceptor.afterExecute(ctx, "{\"files\":[\"Main.java\"]}", task);

        JsonNode root = objectMapper.readTree(result);
        assertThat(root.get("build_exit_code").asInt()).isZero();
        assertThat(root.get("build_success").asBoolean()).isTrue();
        assertThat(root.get("build_stdout").asText()).isEqualTo("BUILD SUCCESS");
        assertThat(root.get("build_duration_ms").asLong()).isEqualTo(5000);
        assertThat(root.get("build_timed_out").asBoolean()).isFalse();
        // Original fields preserved
        assertThat(root.get("files").isArray()).isTrue();
    }

    @Test
    @DisplayName("afterExecute enriches result with failed build output")
    void afterExecute_failedBuild_enrichesResult() throws Exception {
        AgentContext ctx = makeContext("/workspace/test123");
        AgentTask task = makeTask("BE", "be-java");

        when(sandboxExecutor.execute(any())).thenReturn(
            new SandboxResult(1, "", "COMPILATION ERROR: missing semicolon", 3000, false)
        );

        String result = interceptor.afterExecute(ctx, "{}", task);

        JsonNode root = objectMapper.readTree(result);
        assertThat(root.get("build_exit_code").asInt()).isEqualTo(1);
        assertThat(root.get("build_success").asBoolean()).isFalse();
        assertThat(root.get("build_stderr").asText()).contains("COMPILATION ERROR");
    }

    @Test
    @DisplayName("afterExecute returns original result when sandbox throws exception")
    void afterExecute_sandboxException_returnsOriginal() {
        AgentContext ctx = makeContext("/workspace/test123");
        AgentTask task = makeTask("BE", "be-java");

        when(sandboxExecutor.execute(any())).thenThrow(new RuntimeException("Docker not available"));

        String original = "{\"code\":\"generated\"}";
        String result = interceptor.afterExecute(ctx, original, task);

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("afterExecute handles non-JSON worker output gracefully")
    void afterExecute_nonJsonOutput_wrapsInObject() throws Exception {
        AgentContext ctx = makeContext("/workspace/test123");
        AgentTask task = makeTask("FE", "fe-react");

        when(sandboxExecutor.execute(any())).thenReturn(
            new SandboxResult(0, "ok", "", 1000, false)
        );

        String result = interceptor.afterExecute(ctx, "plain text output", task);

        JsonNode root = objectMapper.readTree(result);
        assertThat(root.get("worker_output").asText()).isEqualTo("plain text output");
        assertThat(root.get("build_success").asBoolean()).isTrue();
    }
}
