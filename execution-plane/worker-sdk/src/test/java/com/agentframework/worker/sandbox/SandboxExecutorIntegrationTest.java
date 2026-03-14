package com.agentframework.worker.sandbox;

import com.agentframework.common.sandbox.SandboxRequest;
import com.agentframework.common.sandbox.SandboxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link SandboxExecutor} — require a running Docker daemon
 * and pre-built sandbox images. Run with {@code -Dgroups=integration}.
 *
 * <p>These tests verify that the sandbox containers actually execute build
 * commands correctly with the fixed tmpfs mounts and read-only workspace.</p>
 */
@Tag("integration")
class SandboxExecutorIntegrationTest {

    private final SandboxExecutor executor = new SandboxExecutor(2);

    @Test
    @DisplayName("Java sandbox compiles a simple Main.java via copy-to-tmp strategy")
    void javaSandbox_compilesSimpleClass(@TempDir Path workspace) throws IOException {
        // Create a minimal Maven project
        Files.writeString(workspace.resolve("pom.xml"),
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>test</groupId>
              <artifactId>sandbox-test</artifactId>
              <version>1.0</version>
            </project>
            """);

        Path srcDir = workspace.resolve("src/main/java");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"),
            """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello from sandbox!");
                }
            }
            """);

        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-java:21",
            List.of("cp -r /code /tmp/build && cd /tmp/build && mvn compile -q -B"),
            workspace.toString(),
            null,
            512, 1.0, 120, true
        );

        SandboxResult result = executor.execute(request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Python sandbox validates syntax of a .py file")
    void pythonSandbox_checksSyntax(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("app.py"),
            """
            def greet(name: str) -> str:
                return f"Hello, {name}!"

            if __name__ == "__main__":
                print(greet("sandbox"))
            """);

        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-python:3.12",
            List.of("python -m py_compile /code/app.py"),
            workspace.toString(),
            null,
            512, 1.0, 60, true
        );

        SandboxResult result = executor.execute(request);

        assertThat(result.exitCode()).isZero();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Sandbox respects timeout and kills long-running processes")
    void sandbox_respectsTimeout(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("dummy.txt"), "test");

        SandboxRequest request = new SandboxRequest(
            "agent-sandbox-python:3.12",
            List.of("sleep 300"),
            workspace.toString(),
            null,
            256, 1.0, 3, true  // 3 second timeout
        );

        SandboxResult result = executor.execute(request);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(137);
    }
}
