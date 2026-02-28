package com.agentframework.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for worker SDK.
 * Set in each worker application's application.yml:
 *
 * agent.worker:
 *   task-topic: agent-tasks
 *   task-subscription: be-worker-sub
 *   results-topic: agent-results
 */
@ConfigurationProperties(prefix = "agent.worker")
public class WorkerProperties {

    private String taskTopic;
    private String taskSubscription;
    private String resultsTopic = "agent-results";

    public String getTaskTopic() { return taskTopic; }
    public void setTaskTopic(String taskTopic) { this.taskTopic = taskTopic; }

    public String getTaskSubscription() { return taskSubscription; }
    public void setTaskSubscription(String taskSubscription) { this.taskSubscription = taskSubscription; }

    public String getResultsTopic() { return resultsTopic; }
    public void setResultsTopic(String resultsTopic) { this.resultsTopic = resultsTopic; }
}
