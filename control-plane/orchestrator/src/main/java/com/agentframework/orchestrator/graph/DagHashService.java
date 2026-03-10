package com.agentframework.orchestrator.graph;

import com.agentframework.common.util.HashUtil;
import com.agentframework.orchestrator.domain.Plan;
import com.agentframework.orchestrator.domain.PlanItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes and verifies Merkle-style DAG hashes for plan integrity (#45).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Topological sort (Kahn's algorithm) — sources first, cycle-tolerant</li>
 *   <li>For each item in topo order: {@code dagHash = SHA-256(taskKey|workerType|title|sorted(predecessors.dagHash))}</li>
 *   <li>Sink nodes = items whose taskKey does not appear in any other item's dependsOn</li>
 *   <li>{@code merkleRoot = SHA-256(sorted(sinkNodes.dagHash))}</li>
 * </ol>
 */
@Service
public class DagHashService {

    private static final Logger log = LoggerFactory.getLogger(DagHashService.class);

    /**
     * Recomputes dagHash for every item and merkleRoot on the plan.
     * Mutates in-place — the caller is responsible for persisting.
     */
    public void recomputeHashes(Plan plan) {
        List<PlanItem> items = plan.getItems();
        if (items == null || items.isEmpty()) {
            plan.setMerkleRoot(null);
            return;
        }

        Map<String, PlanItem> byKey = items.stream()
                .collect(Collectors.toMap(PlanItem::getTaskKey, i -> i));

        List<PlanItem> sorted = kahnSort(items, byKey);

        // Compute dagHash in topological order (predecessors already hashed)
        for (PlanItem item : sorted) {
            String depHashes = item.getDependsOn().stream()
                    .map(byKey::get)
                    .filter(Objects::nonNull)
                    .map(PlanItem::getDagHash)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.joining(","));

            String payload = item.getTaskKey() + "|"
                    + item.getWorkerType().name() + "|"
                    + item.getTitle() + "|"
                    + depHashes;

            item.setDagHash(HashUtil.sha256(payload));
        }

        // Sink nodes: items whose taskKey is NOT referenced in any dependsOn
        Set<String> referenced = items.stream()
                .flatMap(i -> i.getDependsOn().stream())
                .collect(Collectors.toSet());

        String sinkHashes = items.stream()
                .filter(i -> !referenced.contains(i.getTaskKey()))
                .map(PlanItem::getDagHash)
                .filter(Objects::nonNull)
                .sorted()
                .collect(Collectors.joining(","));

        plan.setMerkleRoot(HashUtil.sha256(sinkHashes));

        log.debug("Computed merkle root for plan {}: {}", plan.getId(), plan.getMerkleRoot());
    }

    /**
     * Verifies DAG integrity by recomputing hashes and comparing with stored values.
     * Does NOT mutate the plan — snapshots, recomputes, compares, then restores.
     *
     * @return verification result with mismatched task keys (if any)
     */
    public DagVerificationResult verify(Plan plan) {
        List<PlanItem> items = plan.getItems();
        if (items == null || items.isEmpty()) {
            return new DagVerificationResult(
                    plan.getMerkleRoot() == null,
                    null, plan.getMerkleRoot(),
                    List.of(), 0);
        }

        // Snapshot current hashes
        Map<String, String> savedDagHashes = items.stream()
                .collect(Collectors.toMap(PlanItem::getTaskKey, i -> i.getDagHash() != null ? i.getDagHash() : ""));
        String savedMerkleRoot = plan.getMerkleRoot();

        // Recompute
        recomputeHashes(plan);

        // Compare
        List<String> mismatched = new ArrayList<>();
        for (PlanItem item : items) {
            String saved = savedDagHashes.getOrDefault(item.getTaskKey(), "");
            String computed = item.getDagHash() != null ? item.getDagHash() : "";
            if (!saved.equals(computed)) {
                mismatched.add(item.getTaskKey());
            }
        }

        String computedRoot = plan.getMerkleRoot();
        boolean rootMatch = Objects.equals(savedMerkleRoot, computedRoot);
        boolean valid = mismatched.isEmpty() && rootMatch;

        // Restore original hashes
        for (PlanItem item : items) {
            String original = savedDagHashes.get(item.getTaskKey());
            item.setDagHash(original.isEmpty() ? null : original);
        }
        plan.setMerkleRoot(savedMerkleRoot);

        return new DagVerificationResult(valid, computedRoot, savedMerkleRoot, mismatched, items.size());
    }

    // ── Kahn's topological sort ─────────────────────────────────────────────

    /**
     * BFS topological sort. Items with unresolvable dependencies (cycles or missing keys)
     * are appended at the end rather than causing an error.
     */
    private List<PlanItem> kahnSort(List<PlanItem> items, Map<String, PlanItem> byKey) {
        // Compute in-degree (only counting edges within the plan)
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (PlanItem item : items) {
            inDegree.putIfAbsent(item.getTaskKey(), 0);
            for (String dep : item.getDependsOn()) {
                if (byKey.containsKey(dep)) {
                    inDegree.merge(item.getTaskKey(), 1, Integer::sum);
                }
            }
        }

        // Seed queue with sources (in-degree 0)
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : inDegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }

        List<PlanItem> sorted = new ArrayList<>(items.size());
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String key = queue.poll();
            if (!visited.add(key)) continue;
            PlanItem item = byKey.get(key);
            if (item == null) continue;
            sorted.add(item);

            // Reduce in-degree of successors
            for (PlanItem candidate : items) {
                if (candidate.getDependsOn().contains(key)) {
                    int newDeg = inDegree.merge(candidate.getTaskKey(), -1, Integer::sum);
                    if (newDeg == 0 && !visited.contains(candidate.getTaskKey())) {
                        queue.add(candidate.getTaskKey());
                    }
                }
            }
        }

        // Append any remaining items (cycle members) — hash them with whatever predecessors were resolved
        for (PlanItem item : items) {
            if (!visited.contains(item.getTaskKey())) {
                sorted.add(item);
            }
        }

        return sorted;
    }
}
