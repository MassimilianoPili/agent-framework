package com.agentframework.worker.sandbox;

import com.agentframework.common.sandbox.SandboxRequest;
import com.agentframework.common.sandbox.SandboxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SandboxExecutor}.
 *
 * <p>Tests the Docker command construction logic (pure functions, no Docker daemon needed).
 * Integration tests with actual Docker require @Tag("integration").</p>
 */
class SandboxExecutorTest {

    private final SandboxExecutor executor = new SandboxExecutor(2);

    @Test
    @DisplayName("buildDockerCommand includes all security flags")
    void buildDockerCommand_includesSecurityFlags() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-java:21",
            List.of("mvn", "compile", "-q"),
            "/workspace/abc12345",
            "/workspace/abc12345/out",
            512, 1.0, 120, true
        );

        List<String> cmd = executor.buildDockerCommand(request);

        assertThat(cmd).contains("docker", "run", "--rm");
        assertThat(cmd).contains("--network", "none");
        assertThat(cmd).contains("--read-only");
        assertThat(cmd).contains("--user", "1000:1000");
        assertThat(cmd).contains("--memory", "512m");
        assertThat(cmd).contains("--cpus", "1.0");
        assertThat(cmd).contains("agent-sandbox-java:21");
    }

    @Test
    @DisplayName("buildDockerCommand mounts workspace read-only and output read-write")
    void buildDockerCommand_volumeMounts() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-java:21",
            List.of("mvn", "compile"),
            "/workspace/abc12345",
            "/workspace/abc12345/out"
        );

        List<String> cmd = executor.buildDockerCommand(request);

        assertThat(cmd).contains("-v", "/workspace/abc12345:/code:ro");
        assertThat(cmd).contains("-v", "/workspace/abc12345/out:/out:rw");
    }

    @Test
    @DisplayName("buildDockerCommand wraps command in sh -c")
    void buildDockerCommand_wrapsInShell() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-java:21",
            List.of("mvn", "compile", "-q", "-B"),
            "/workspace/test",
            "/workspace/test/out"
        );

        List<String> cmd = executor.buildDockerCommand(request);

        // Last 3 elements: sh -c "mvn compile -q -B"
        assertThat(cmd.get(cmd.size() - 3)).isEqualTo("sh");
        assertThat(cmd.get(cmd.size() - 2)).isEqualTo("-c");
        assertThat(cmd.get(cmd.size() - 1)).isEqualTo("mvn compile -q -B");
    }

    @Test
    @DisplayName("buildDockerCommand with network enabled omits --network none")
    void buildDockerCommand_networkEnabled() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-node:22",
            List.of("npm", "install"),
            "/workspace/test",
            null,
            512, 1.0, 120, false  // network enabled
        );

        List<String> cmd = executor.buildDockerCommand(request);

        assertThat(cmd).doesNotContain("--network");
    }

    @Test
    @DisplayName("buildDockerCommand without output path skips output volume mount")
    void buildDockerCommand_noOutputPath() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-python:3.12",
            List.of("python", "-m", "py_compile", "main.py"),
            "/workspace/test",
            null,
            512, 1.0, 120, true
        );

        List<String> cmd = executor.buildDockerCommand(request);

        // Should have workspace mount but not output mount
        long volumeArgs = cmd.stream().filter(s -> s.contains(":/out:")).count();
        assertThat(volumeArgs).isZero();
    }

    @Test
    @DisplayName("buildDockerCommand includes tmpfs for /tmp without noexec")
    void buildDockerCommand_includesTmpfsWithoutNoexec() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-java:21",
            List.of("mvn", "compile"),
            "/workspace/test",
            "/workspace/test/out"
        );

        List<String> cmd = executor.buildDockerCommand(request);

        // /tmp must be writable and executable (no noexec — Go/Rust/Maven need it)
        assertThat(cmd).contains("--tmpfs", "/tmp:rw,nosuid,size=256m");
        // Must NOT have noexec
        assertThat(cmd.stream().anyMatch(s -> s.contains("/tmp") && s.contains("noexec"))).isFalse();
    }

    @Test
    @DisplayName("buildDockerCommand includes build tool cache tmpfs mounts")
    void buildDockerCommand_includesCacheTmpfs() {
        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-java:21",
            List.of("mvn", "compile"),
            "/workspace/test",
            "/workspace/test/out"
        );

        List<String> cmd = executor.buildDockerCommand(request);

        assertThat(cmd).contains("--tmpfs", "/home/sandbox/.m2:rw,size=256m");
        assertThat(cmd).contains("--tmpfs", "/home/sandbox/.cache:rw,size=128m");
    }

    @Test
    @DisplayName("SandboxResult.isSuccess returns true only when exit 0 and no timeout")
    void sandboxResult_isSuccess() {
        assertThat(new SandboxResult(0, "", "", 100, false).isSuccess()).isTrue();
        assertThat(new SandboxResult(1, "", "", 100, false).isSuccess()).isFalse();
        assertThat(new SandboxResult(0, "", "", 100, true).isSuccess()).isFalse();
        assertThat(new SandboxResult(137, "", "", 120000, true).isSuccess()).isFalse();
    }
}
