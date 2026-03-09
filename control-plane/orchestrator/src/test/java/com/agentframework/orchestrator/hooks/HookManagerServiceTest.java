package com.agentframework.orchestrator.hooks;

import com.agentframework.common.policy.HookPolicy;
import com.agentframework.orchestrator.domain.WorkerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HookManagerService} — policy storage and resolution
 * for HOOK_MANAGER and TOOL_MANAGER worker results.
 */
class HookManagerServiceTest {

    private HookManagerService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        HookPolicyResolver resolver = mock(HookPolicyResolver.class);
        service = new HookManagerService(objectMapper, resolver);
    }

    // ── storeToolManagerResult — valid JSON stores policy ─────────────────────

    @Test
    void storeToolManagerResult_validJson_storesPolicy() {
        UUID planId = UUID.randomUUID();
        String json = """
                {
                  "target_task_key": "BE-001",
                  "allowedTools": ["fs_read", "fs_write", "fs_list"],
                  "ownedPaths": ["src/main/java/com/example/"],
                  "allowedMcpServers": ["repo-fs"],
                  "rationale": "BE task needs write access"
                }
                """;

        service.storeToolManagerResult(planId, json);

        Optional<HookPolicy> policy = service.resolvePolicy(planId, "BE-001", WorkerType.BE);
        assertThat(policy).isPresent();
        assertThat(policy.get().allowedTools()).containsExactly("fs_read", "fs_write", "fs_list");
        assertThat(policy.get().ownedPaths()).containsExactly("src/main/java/com/example/");
        assertThat(policy.get().allowedMcpServers()).containsExactly("repo-fs");
    }

    // ── storeToolManagerResult — overrides HookManager policy ─────────────────

    @Test
    void storeToolManagerResult_overridesHookManagerPolicy() {
        UUID planId = UUID.randomUUID();

        // First: store HM result with policies for BE-001 and FE-001
        String hmJson = """
                {
                  "policies": {
                    "BE-001": {
                      "allowedTools": ["fs_read"],
                      "ownedPaths": [],
                      "allowedMcpServers": []
                    },
                    "FE-001": {
                      "allowedTools": ["fs_read", "fs_write"],
                      "ownedPaths": ["frontend/"],
                      "allowedMcpServers": []
                    }
                  }
                }
                """;
        service.storePolicies(planId, hmJson);

        // Verify HM policy is in place
        assertThat(service.resolvePolicy(planId, "BE-001", WorkerType.BE))
                .isPresent()
                .get()
                .extracting(HookPolicy::allowedTools)
                .asList().containsExactly("fs_read");

        // Then: store TM result that overrides BE-001
        String tmJson = """
                {
                  "target_task_key": "BE-001",
                  "allowedTools": ["fs_read", "fs_write", "bash_execute"],
                  "ownedPaths": ["backend/src/"],
                  "allowedMcpServers": ["repo-fs"]
                }
                """;
        service.storeToolManagerResult(planId, tmJson);

        // BE-001 should now have TM's policy (overridden)
        Optional<HookPolicy> bePolicy = service.resolvePolicy(planId, "BE-001", WorkerType.BE);
        assertThat(bePolicy).isPresent();
        assertThat(bePolicy.get().allowedTools())
                .containsExactly("fs_read", "fs_write", "bash_execute");
        assertThat(bePolicy.get().ownedPaths()).containsExactly("backend/src/");

        // FE-001 should still have HM's original policy (not affected by TM)
        Optional<HookPolicy> fePolicy = service.resolvePolicy(planId, "FE-001", WorkerType.FE);
        assertThat(fePolicy).isPresent();
        assertThat(fePolicy.get().allowedTools()).containsExactly("fs_read", "fs_write");
        assertThat(fePolicy.get().ownedPaths()).containsExactly("frontend/");
    }

    // ── storeToolManagerResult — empty JSON is no-op ──────────────────────────

    @Test
    void storeToolManagerResult_emptyJson_noOp() {
        UUID planId = UUID.randomUUID();

        service.storeToolManagerResult(planId, "");
        service.storeToolManagerResult(planId, null);
        service.storeToolManagerResult(planId, "   ");

        // No policies stored
        Optional<HookPolicy> policy = service.resolvePolicy(planId, "BE-001", WorkerType.BE);
        // Should fall through to resolver (mocked, returns empty)
        assertThat(policy).isEmpty();
    }

    // ── storeToolManagerResult — missing target_task_key is no-op ─────────────

    @Test
    void storeToolManagerResult_noTargetTaskKey_noOp() {
        UUID planId = UUID.randomUUID();
        String json = """
                {
                  "allowedTools": ["fs_read"],
                  "ownedPaths": [],
                  "allowedMcpServers": []
                }
                """;

        service.storeToolManagerResult(planId, json);

        // No policy stored (missing target_task_key)
        Optional<HookPolicy> policy = service.resolvePolicy(planId, "BE-001", WorkerType.BE);
        assertThat(policy).isEmpty();
    }
}
