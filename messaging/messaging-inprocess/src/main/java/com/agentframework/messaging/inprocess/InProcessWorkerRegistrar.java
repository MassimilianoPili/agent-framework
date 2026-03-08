package com.agentframework.messaging.inprocess;

import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Discovers all {@link AbstractWorker} beans and registers each as a handler
 * in the {@link InProcessMessageBroker}.
 *
 * <p>This replaces the single-worker {@code WorkerTaskConsumer} approach used in
 * standalone JVM mode. In consolidated mode (Phase 1b of #29), all 46+ workers
 * share the same JVM and broker.
 *
 * <p>Each worker is registered with a group name following the convention
 * {@code {workerType}-{workerProfile}-worker-group}. The broker routes messages
 * by matching the envelope's {@code workerType} property to the group name prefix.
 * Fine-grained profile matching is done within the handler via {@link #shouldProcess}.
 *
 * <p>Message flow:
 * <pre>
 * OrchestrationService → AgentTaskProducer → InProcessMessageSender
 *   → InProcessMessageBroker.dispatch() → findHandler(workerType)
 *     → handler lambda → shouldProcess(worker, task) → worker.process(task)
 *       → WorkerResultProducer → InProcessMessageSender → broker → AgentResultConsumer
 * </pre>
 *
 * <p><strong>Hybrid mode (#29 Phase 2)</strong>: when {@code worker-runtime.remote-types}
 * is configured, workers of those types are skipped (they run in separate JVMs).
 * Only manager workers (not in the remote-types list) are registered in-process.
 */
@Component
@ConditionalOnBean(InProcessMessageBroker.class)
public class InProcessWorkerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(InProcessWorkerRegistrar.class);

    private static final String TASK_TOPIC = "agent-tasks";

    private final InProcessMessageBroker broker;
    private final ObjectProvider<List<AbstractWorker>> workersProvider;
    private final ObjectMapper objectMapper;
    private final Set<String> remoteTypes;

    public InProcessWorkerRegistrar(InProcessMessageBroker broker,
                                     ObjectProvider<List<AbstractWorker>> workersProvider,
                                     ObjectMapper objectMapper,
                                     @Value("${worker-runtime.remote-types:}") String remoteTypesCSV) {
        this.broker = broker;
        this.workersProvider = workersProvider;
        this.objectMapper = objectMapper;
        this.remoteTypes = (remoteTypesCSV == null || remoteTypesCSV.isBlank())
                ? Set.of()
                : Arrays.stream(remoteTypesCSV.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    @PostConstruct
    public void registerAllWorkers() {
        List<AbstractWorker> workers = workersProvider.getIfAvailable(List::of);
        if (workers.isEmpty()) {
            log.warn("InProcess: no AbstractWorker beans found — no workers registered");
            return;
        }

        int skipped = 0;
        for (AbstractWorker worker : workers) {
            // In hybrid mode, skip workers whose type is handled by remote JVMs
            if (remoteTypes.contains(worker.workerType())) {
                log.info("InProcess: skipping remote worker type={} profile={} (handled by remote JVM)",
                        worker.workerType(), worker.workerProfile());
                skipped++;
                continue;
            }

            String group = worker.workerType() + "-" + worker.workerProfile() + "-worker-group";
            broker.register(TASK_TOPIC, group, (body, ack) -> {
                try {
                    AgentTask task = objectMapper.readValue(body, AgentTask.class);
                    if (shouldProcess(worker, task)) {
                        log.info("InProcess: dispatching task '{}' to worker type={} profile={}",
                                task.taskKey(), worker.workerType(), worker.workerProfile());
                        worker.process(task);
                    } else {
                        log.debug("InProcess: skipping task '{}' (type={}, profile={}) — not for worker {}/{}",
                                task.taskKey(), task.workerType(), task.workerProfile(),
                                worker.workerType(), worker.workerProfile());
                    }
                    ack.complete();
                } catch (Exception e) {
                    log.error("InProcess: failed to handle task for worker {}/{}: {}",
                            worker.workerType(), worker.workerProfile(), e.getMessage(), e);
                    ack.reject(e.getMessage());
                }
            });
            log.info("InProcess: registered worker type={} profile={} group={}",
                    worker.workerType(), worker.workerProfile(), group);
        }

        int registered = workers.size() - skipped;
        log.info("InProcess: {} workers registered with broker ({} skipped as remote)",
                registered, skipped);
    }

    /**
     * Determines whether a worker should process the given task.
     *
     * <p>Rules (same as {@code WorkerTaskConsumer.shouldProcess}):
     * <ol>
     *   <li>workerType must match</li>
     *   <li>If both task and worker specify a profile, they must match</li>
     * </ol>
     */
    private boolean shouldProcess(AbstractWorker worker, AgentTask task) {
        if (!worker.workerType().equals(task.workerType())) {
            return false;
        }
        String taskProfile = task.workerProfile();
        String myProfile = worker.workerProfile();
        if (taskProfile != null && !taskProfile.isBlank()
                && myProfile != null && !myProfile.isBlank()
                && !taskProfile.equals(myProfile)) {
            return false;
        }
        return true;
    }
}
