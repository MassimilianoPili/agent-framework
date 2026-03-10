# #44 — Execution Sandbox Containerizzato (Framework + Worker Isolation)

> Spostato da PIANO.md — design reference per implementazione futura.

**Problema**: i worker domain (BE, FE) generano codice ma non possono compilarlo né testarlo.
`mcp-bash-tool` (#25) permetterebbe l'esecuzione shell, ma sul filesystem del server —
conflitti di librerie, versioni incompatibili, rischio sicurezza.

**Soluzione**: Approccio C (dual-layer) — il framework gira in container Docker (orchestratore + N worker),
e quando un worker deve compilare/testare, spawna un sandbox container effimero via Docker socket.

## Livello 1 — Framework containerizzato (Docker Compose)

```yaml
services:
  orchestrator:
    build: .
    profiles: [orchestrator]
    environment:
      SPRING_PROFILES_ACTIVE: orchestrator
      WORKER_RUNTIMES: >
        {"AI_TASK":"http://worker-ai:8100",
         "REVIEW":"http://worker-review:8101"}
    mem_limit: 256m
    networks: [agent-net, shared]
    depends_on: [redis, postgres]

  worker-ai:
    build: .
    profiles: [worker]
    environment:
      SPRING_PROFILES_ACTIVE: worker
      WORKER_TYPE: AI_TASK
      SERVER_PORT: 8100
      DOCKER_HOST: unix:///var/run/docker.sock
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - workspace:/workspace
    mem_limit: 1g
    networks: [agent-net]

networks:
  agent-net:
    internal: true
  shared:
    external: true
```

## Livello 2 — Execution Sandbox (container effimeri)

```java
public class SandboxExecutor {
    private final DockerClient docker;

    public SandboxResult execute(SandboxRequest request) {
        String containerId = docker.createContainer(
            ContainerConfig.builder()
                .image(request.sandboxImage())
                .cmd(request.command())
                .networkDisabled(true)
                .readonlyRootfs(true)
                .memory(request.memoryLimit())
                .cpuQuota(100_000)
                .user("1000:1000")
                .binds(List.of(
                    request.workspacePath() + ":/code:ro",
                    request.outputPath() + ":/out:rw"
                ))
                .build()
        ).getId();

        docker.startContainer(containerId);
        WaitResponse wait = docker.waitContainer(containerId, request.timeoutSeconds());

        String stdout = docker.logs(containerId, STDOUT);
        String stderr = docker.logs(containerId, STDERR);
        int exitCode = wait.statusCode();

        docker.removeContainer(containerId, true);

        return new SandboxResult(exitCode, stdout, stderr, readOutputFiles(request.outputPath()));
    }
}
```

## Sandbox images pre-built

| Image | Base | Toolchain | Size |
|-------|------|-----------|------|
| `agent-sandbox-java:21` | eclipse-temurin:21-jdk-alpine | Maven 3.9, Gradle 8 | ~350 MB |
| `agent-sandbox-cobol` | alpine:3.21 | GnuCOBOL 3.2, gcc, copybook parser | ~150 MB |
| `agent-sandbox-go:1.22` | golang:1.22-alpine | go, golangci-lint | ~300 MB |
| `agent-sandbox-python:3.12` | python:3.12-slim | pip, pytest, ruff | ~200 MB |
| `agent-sandbox-node:22` | node:22-alpine | npm, pnpm, vitest | ~200 MB |
| `agent-sandbox-rust` | rust:1-alpine | cargo, clippy | ~500 MB |
| `agent-sandbox-cpp` | gcc:14 | gcc, cmake, make, valgrind | ~300 MB |
| `agent-sandbox-dotnet` | mcr.microsoft.com/dotnet/sdk:8.0-alpine | dotnet CLI | ~400 MB |

## Sicurezza (difesa in profondita')

| Livello | Meccanismo | Cosa blocca |
|---------|-----------|-------------|
| 1 | `--network none` | Nessun accesso internet o rete Docker |
| 2 | `--read-only` + tmpfs selettivi | Filesystem immutabile (tranne /tmp e /out) |
| 3 | `--user 1000:1000` | Non-root, nessun capability Linux |
| 4 | `--memory 512m --cpus 1` | DoS prevention |
| 5 | Timeout (default 120s, max 600s) | Fork bomb, loop infiniti |
| 6 | Volume codice `:ro` | Il sandbox non puo' modificare il sorgente |
| 7 | No Docker socket nel sandbox | Il sandbox non puo' spawnare altri container |
| 8 | seccomp profile (opzionale) | Blocca syscall pericolose |

## File da creare

| File | Modulo | Descrizione |
|------|--------|-------------|
| `SandboxExecutor.java` | worker-sdk | Spawna container Docker effimeri |
| `SandboxRequest.java` | agent-common | Record: image, command, files, limits |
| `SandboxResult.java` | agent-common | Record: exitCode, stdout, stderr, outputFiles |
| `SandboxProperties.java` | worker-sdk | Config: default image per workerType, limiti, timeout |
| `docker-compose.yml` | root | Framework containerizzato |
| `Dockerfile.sandbox-*` | sandbox/ | Immagini sandbox per linguaggio |

**Sforzo**: 3g. **Dipendenze**: #29 (Worker Lifecycle), #25 (mcp-bash-tool).
