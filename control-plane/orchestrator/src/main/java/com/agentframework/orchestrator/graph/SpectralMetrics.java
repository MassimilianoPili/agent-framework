package com.agentframework.orchestrator.graph;

import java.util.List;
import java.util.UUID;

/**
 * Results of spectral analysis on a plan's task DAG.
 *
 * <p>The Laplacian spectrum reveals structural properties of the dependency graph:
 * <ul>
 *   <li><b>Fiedler value</b> (λ₁, second-smallest eigenvalue): measures algebraic connectivity.
 *       High → robust graph; low → fragile, single bottleneck.</li>
 *   <li><b>Spectral gap</b> (λ₁ / λ_max): high → tightly connected; low → modular sub-plans.</li>
 *   <li><b>Partition</b>: bi-partition from the Fiedler vector — splits the DAG into two
 *       loosely coupled clusters for potential parallel execution.</li>
 *   <li><b>Bottlenecks</b>: nodes near the partition boundary (|fiedler[i]| ≈ 0) that bridge
 *       the two clusters — removing them would disconnect the graph.</li>
 * </ul>
 *
 * @see SpectralAnalyzer
 * @see <a href="https://doi.org/10.21136/CMJ.1973.101168">Fiedler (1973)</a>
 */
public record SpectralMetrics(
    UUID planId,
    double fiedlerValue,
    double spectralGap,
    double algebraicConnectivity,
    List<List<String>> partition,
    List<String> bottlenecks,
    double[] eigenvalues,
    int nodeCount,
    int edgeCount
) {}
