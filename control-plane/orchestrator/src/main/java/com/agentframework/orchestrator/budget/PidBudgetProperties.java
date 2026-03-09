package com.agentframework.orchestrator.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the PID-based adaptive token budget controller (#37).
 *
 * <p>The PID controller adjusts {@code HookPolicy.maxTokenBudget} per (planId, workerType)
 * based on the cumulative error between estimated and actual token consumption.
 * Zero LLM cost — purely in-memory feedback loop.</p>
 *
 * <p>Disabled by default. Enable with {@code budget.pid.enabled=true}.</p>
 *
 * @param enabled        master switch (default false)
 * @param kp             proportional gain — reacts to current error
 * @param ki             integral gain — eliminates steady-state bias
 * @param kd             derivative gain — dampens oscillation
 * @param minBudget      floor for adjusted maxTokenBudget (tokens)
 * @param maxBudget      ceiling for adjusted maxTokenBudget (tokens)
 * @param integralLimit  anti-windup clamp for the integral accumulator
 * @param warmupSamples  minimum completed tasks before PID activates (uses passthrough until then)
 */
@ConfigurationProperties(prefix = "budget.pid")
public record PidBudgetProperties(
    boolean enabled,
    double kp,
    double ki,
    double kd,
    int minBudget,
    int maxBudget,
    double integralLimit,
    int warmupSamples
) {}
