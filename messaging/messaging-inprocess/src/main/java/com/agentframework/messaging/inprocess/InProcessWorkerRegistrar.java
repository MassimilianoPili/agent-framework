package com.agentframework.messaging.inprocess;

import com.agentframework.worker.AbstractWorker;
import com.agentframework.worker.dto.AgentTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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
 */
@Component
@ConditionalOnProperty(name = "messaging.provider", havingValue = "in-process")
public class InProcessWorkerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(InProcessWorkerRegistrar.class);

    private static final String TASK_TOPIC = "agent-tasks";

    private final InProcessMessageBroker broker;
    private final ObjectProvider<List<AbstractWorker>> workersProvider;
    private final ObjectMapper objectMapper;

    public InProcessWorkerRegistrar(InProcessMessageBroker broker,
                                     ObjectProvider<List<AbstractWorker>> workersProvider,
                                     ObjectMapper objectMapper) {
        this.broker = broker;
        this.workersProvider = workersProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void registerAllWorkers() {
        List<AbstractWorker> workers = workersProvider.getIfAvailable(List::of);
        if (workers.isEmpty()) {
            log.warn("InProcess: no AbstractWorker beans found — no workers registered");
            return;
        }

        for (AbstractWorker worker : workers) {
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

        log.info("InProcess: {} workers registered with broker", workers.size());
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
