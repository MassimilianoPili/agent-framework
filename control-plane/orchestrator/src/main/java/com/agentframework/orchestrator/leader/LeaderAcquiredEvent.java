package com.agentframework.orchestrator.leader;

/**
 * Published when this orchestrator instance acquires the leader lock.
 * Consumers (e.g. AgentResultConsumer) start processing on receipt of this event.
 */
public record LeaderAcquiredEvent(String instanceId) {}
