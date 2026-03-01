package com.agentframework.gp.model;

/**
 * A single training sample for GP fitting.
 *
 * @param embedding     task embedding (1024 dim from mxbai-embed-large via Ollama)
 * @param reward        observed reward (aggregatedReward from RewardComputationService)
 * @param workerProfile the profile that executed this task (e.g. "be-java")
 */
public record TrainingPoint(float[] embedding, double reward, String workerProfile) {}
