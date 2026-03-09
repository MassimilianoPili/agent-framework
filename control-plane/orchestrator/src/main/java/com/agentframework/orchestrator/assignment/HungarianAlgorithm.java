package com.agentframework.orchestrator.assignment;

/**
 * Kuhn-Munkres (Hungarian) algorithm for minimum-cost bipartite assignment (#42).
 *
 * <p>Solves the N×M rectangular assignment problem in O(n³) where n = max(N, M).
 * Uses the potential-based (dual variable) formulation with shortest augmenting
 * paths, following Jonker-Volgenant's efficient variant.</p>
 *
 * <p>Input: cost matrix {@code double[N][M]} where N = rows (tasks), M = columns (profiles).
 * Output: {@code int[N]} where {@code result[i]} = column assigned to row i,
 * or -1 if the row was a dummy (padding for rectangular matrices).</p>
 *
 * <p>Handles {@link Double#POSITIVE_INFINITY} as "incompatible" — these pairs
 * are naturally excluded from the optimal solution unless no feasible assignment
 * exists without them.</p>
 *
 * <p>No Spring dependency — pure algorithm, unit-testable standalone.</p>
 */
public final class HungarianAlgorithm {

    /** Sentinel for unassigned rows/columns. */
    private static final int UNASSIGNED = -1;

    private HungarianAlgorithm() {}

    /**
     * Solves the minimum-cost assignment problem.
     *
     * @param cost N×M cost matrix (may be rectangular; +INF for incompatible pairs)
     * @return assignment[i] = column assigned to row i (0-indexed), or -1 for dummy rows
     * @throws IllegalArgumentException if cost is null or empty
     */
    public static int[] solve(double[][] cost) {
        if (cost == null || cost.length == 0 || cost[0].length == 0) {
            throw new IllegalArgumentException("Cost matrix must be non-null and non-empty");
        }

        int origRows = cost.length;
        int origCols = cost[0].length;
        int n = Math.max(origRows, origCols);

        // Pad to square n×n matrix with 0-cost dummy entries
        double[][] c = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i < origRows && j < origCols) {
                    c[i][j] = cost[i][j];
                }
                // else 0.0 (dummy)
            }
        }

        // u[i], v[j] = row/column potentials (dual variables)
        double[] u = new double[n + 1];
        double[] v = new double[n + 1];

        // p[j] = row assigned to column j (1-indexed internally, 0 = unassigned)
        int[] p = new int[n + 1];

        // way[j] = predecessor column on the augmenting path to column j
        int[] way = new int[n + 1];

        // Process each row: find shortest augmenting path and update potentials
        for (int i = 1; i <= n; i++) {
            // Start augmenting path from virtual column 0
            p[0] = i;
            int j0 = 0; // current column in the path

            double[] minv = new double[n + 1]; // min reduced cost to reach column j
            boolean[] used = new boolean[n + 1]; // column j visited in this iteration

            for (int j = 0; j <= n; j++) {
                minv[j] = Double.MAX_VALUE;
                used[j] = false;
            }

            // Dijkstra-like shortest path in the assignment graph
            boolean pathFound = true;
            do {
                used[j0] = true;
                int i0 = p[j0]; // row assigned to current column (or the row being processed)
                double delta = Double.MAX_VALUE;
                int j1 = UNASSIGNED;

                for (int j = 1; j <= n; j++) {
                    if (used[j]) continue;

                    double cur = c[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }

                // Guard: if no reachable column found (all remaining are +INF), skip row
                if (j1 == UNASSIGNED || delta >= Double.MAX_VALUE / 2) {
                    p[0] = 0; // reset virtual column — row stays unassigned
                    pathFound = false;
                    break;
                }

                // Update potentials along the visited path
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }

                j0 = j1;
            } while (p[j0] != 0);

            // Augment: trace back the path and flip assignments
            if (pathFound) {
                do {
                    int j1 = way[j0];
                    p[j0] = p[j1];
                    j0 = j1;
                } while (j0 != 0);
            }
        }

        // Extract result: for each original row, find its assigned original column
        int[] result = new int[origRows];
        for (int j = 1; j <= n; j++) {
            if (p[j] > 0 && p[j] <= origRows) {
                int col = j - 1;
                if (col < origCols) {
                    result[p[j] - 1] = col;
                } else {
                    result[p[j] - 1] = UNASSIGNED; // assigned to dummy column
                }
            }
        }

        // Mark unassigned rows (shouldn't happen for valid matrices, but safety)
        for (int i = 0; i < origRows; i++) {
            if (result[i] < 0 || result[i] >= origCols) {
                result[i] = UNASSIGNED;
            }
        }

        return result;
    }

    /**
     * Computes the total cost of an assignment.
     *
     * @param cost the original cost matrix
     * @param assignment the assignment array from {@link #solve}
     * @return total cost (sum of cost[i][assignment[i]] for assigned rows)
     */
    public static double totalCost(double[][] cost, int[] assignment) {
        double total = 0;
        for (int i = 0; i < assignment.length; i++) {
            if (assignment[i] >= 0 && assignment[i] < cost[i].length) {
                total += cost[i][assignment[i]];
            }
        }
        return total;
    }
}
