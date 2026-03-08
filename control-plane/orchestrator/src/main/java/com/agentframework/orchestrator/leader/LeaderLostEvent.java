package com.agentframework.orchestrator.leader;

/**
 * Published when this orchestrator instance loses the leader lock (another instance took over,
 * or the TTL expired before a heartbeat could renew it).
 * Consumers (e.g. AgentResultConsumer) stop processing on receipt of this event.
 */
public record LeaderLostEvent(String instanceId) {}
