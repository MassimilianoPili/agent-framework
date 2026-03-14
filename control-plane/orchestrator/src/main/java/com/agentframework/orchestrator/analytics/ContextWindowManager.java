package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Treats the LLM context window as a finite resource and allocates token budget
 * optimally across context chunks using a greedy knapsack algorithm.
 *
 * <p>The available budget is computed as:
 * <pre>
 *   B = maxTokens − systemPromptTokens − toolSchemaTokens − mandatoryContextTokens
 * </pre>
 * Each context chunk has a <b>token cost</b> and a <b>relevance score</b>.
 * The <b>information density</b> is {@code relevance / tokenCount}, and chunks
 * are greedily selected by descending density — a 2-competitive approximation
 * of the 0/1 knapsack problem (Dantzig, 1957).</p>
 *
 * <p>When the utilization ratio exceeds the <b>compaction threshold</b>, the service
 * signals that compaction should be triggered (e.g., summarizing older context).</p>
 *
 * @see <a href="https://arxiv.org/abs/2404.16811">
 *     Context Engineering for AI Agents (2024)</a>
 */
@Service
@ConditionalOnProperty(prefix = "context-engineering", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ContextWindowManager {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowManager.class);

    @Value("${context-engineering.compaction-threshold:0.85}")
    private double compactionThreshold;

    /**
     * Computes the available token budget after subtracting fixed costs.
     *
     * @param maxTokens          the LLM's maximum context window size
     * @param systemPromptTokens tokens consumed by the system prompt
     * @param toolSchemaTokens   tokens consumed by tool schemas
     * @param mandatoryTokens    tokens consumed by mandatory context (e.g., task description)
     * @return available budget for optional context chunks (≥ 0)
     */
    public int computeAvailableBudget(int maxTokens, int systemPromptTokens,
                                       int toolSchemaTokens, int mandatoryTokens) {
        int budget = maxTokens - systemPromptTokens - toolSchemaTokens - mandatoryTokens;
        return Math.max(0, budget);
    }

    /**
     * Allocates token budget across candidate context chunks using greedy knapsack.
     *
     * <p>Chunks are sorted by <b>information density</b> (relevance / tokenCount)
     * in descending order. Each chunk is included if it fits within the remaining budget.
     * This greedy approach is a 2-competitive approximation of the optimal 0/1 knapsack.</p>
     *
     * @param candidates the candidate context chunks to consider
     * @param maxBudget  the maximum token budget available
     * @return allocation result with selected chunks and utilization metrics
     */
    public BudgetAllocation allocate(List<ContextChunk> candidates, int maxBudget) {
        if (candidates == null || candidates.isEmpty() || maxBudget <= 0) {
            return new BudgetAllocation(List.of(), 0, maxBudget, 0.0, false);
        }

        // Sort by information density (relevance / tokenCount) descending
        List<ContextChunk> sorted = candidates.stream()
                .filter(c -> c.tokenCount() > 0)
                .sorted(Comparator.comparingDouble(ContextWindowManager::density).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        List<ContextChunk> selected = new ArrayList<>();
        int budgetUsed = 0;

        for (ContextChunk chunk : sorted) {
            if (budgetUsed + chunk.tokenCount() <= maxBudget) {
                selected.add(chunk);
                budgetUsed += chunk.tokenCount();
            }
        }

        double utilizationRatio = maxBudget > 0 ? (double) budgetUsed / maxBudget : 0.0;
        boolean compactionNeeded = utilizationRatio >= compactionThreshold;

        if (compactionNeeded) {
            log.info("Context budget utilization {:.2%} exceeds compaction threshold {:.2%} — compaction recommended",
                     utilizationRatio, compactionThreshold);
        }

        log.debug("Context allocation: {}/{} tokens used ({} chunks selected out of {}), utilization={:.2%}",
                  budgetUsed, maxBudget, selected.size(), candidates.size(), utilizationRatio);

        return new BudgetAllocation(
                Collections.unmodifiableList(selected),
                budgetUsed,
                maxBudget,
                utilizationRatio,
                compactionNeeded
        );
    }

    /**
     * Computes the information density of a context chunk.
     *
     * @param chunk the context chunk
     * @return density = relevanceScore / tokenCount
     */
    static double density(ContextChunk chunk) {
        return chunk.tokenCount() > 0 ? chunk.relevanceScore() / chunk.tokenCount() : 0.0;
    }

    /**
     * A candidate context chunk to be considered for inclusion in the LLM context window.
     *
     * @param label          human-readable label (e.g., "dependency-result:CM-001", "rag-chunk:3")
     * @param content        the actual text content
     * @param tokenCount     estimated token count for this chunk
     * @param relevanceScore relevance score [0, 1] — higher means more relevant to the current task
     */
    public record ContextChunk(
            String label,
            String content,
            int tokenCount,
            double relevanceScore
    ) {}

    /**
     * Result of a budget allocation.
     *
     * @param selected          the chunks selected for inclusion (ordered by density)
     * @param budgetUsed        total tokens consumed by selected chunks
     * @param totalBudget       the maximum token budget that was available
     * @param utilizationRatio  fraction of budget used (budgetUsed / totalBudget)
     * @param compactionNeeded  true if utilization exceeds the compaction threshold
     */
    public record BudgetAllocation(
            List<ContextChunk> selected,
            int budgetUsed,
            int totalBudget,
            double utilizationRatio,
            boolean compactionNeeded
    ) {}
}
