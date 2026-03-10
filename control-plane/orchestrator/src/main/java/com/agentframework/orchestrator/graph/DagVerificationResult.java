package com.agentframework.orchestrator.graph;

import java.util.List;

/**
 * Result of a DAG integrity verification (#45).
 *
 * @param valid               true if all dagHashes and the merkleRoot match recomputed values
 * @param computedMerkleRoot  the freshly-computed merkle root (null if plan has no items)
 * @param storedMerkleRoot    the merkle root stored on the Plan entity
 * @param mismatchedTaskKeys  task keys whose dagHash differs from the recomputed value
 * @param totalItems          total number of items in the plan
 */
public record DagVerificationResult(
        boolean valid,
        String computedMerkleRoot,
        String storedMerkleRoot,
        List<String> mismatchedTaskKeys,
        int totalItems
) {}
