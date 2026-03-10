package com.agentframework.orchestrator.council;

import java.util.List;

/**
 * A council recommendation weighted by Quadratic Voting (#49).
 *
 * <p>Each recommendation carries its aggregate vote total and the list of
 * council members who voted for it. Higher {@code totalVotes} indicates
 * stronger collective conviction — the quadratic cost means members had
 * to sacrifice budget to express that intensity.</p>
 *
 * @param id         short identifier (e.g. "R1", "R2") used to merge votes across members
 * @param text       actionable recommendation text
 * @param totalVotes aggregate votes across all members (sum of individual allocations)
 * @param voters     profiles of members who voted for this recommendation
 */
public record WeightedRecommendation(
    String id,
    String text,
    int totalVotes,
    List<String> voters
) {}
