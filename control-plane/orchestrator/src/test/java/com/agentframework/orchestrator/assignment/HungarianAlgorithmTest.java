package com.agentframework.orchestrator.assignment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HungarianAlgorithm} — Kuhn-Munkres O(n³) assignment (#42).
 */
class HungarianAlgorithmTest {

    private static final double INF = Double.POSITIVE_INFINITY;

    @Test
    void squareMatrix_3x3_findsOptimal() {
        // Classic example:
        //   [10  5  13]     Optimal: row0→col1(5), row1→col2(7), row2→col0(5)
        //   [ 3  7   7]     Total cost = 5 + 7 + 5 = 17
        //   [ 5  8  12]
        double[][] cost = {
                {10, 5, 13},
                { 3, 7,  7},
                { 5, 8, 12}
        };

        int[] result = HungarianAlgorithm.solve(cost);
        double total = HungarianAlgorithm.totalCost(cost, result);

        assertEquals(17.0, total, 0.001);
        // Verify each row is assigned to a unique column
        assertNotEquals(result[0], result[1]);
        assertNotEquals(result[0], result[2]);
        assertNotEquals(result[1], result[2]);
    }

    @Test
    void rectangularMoreRowsThanCols_padsDummyCols() {
        // 3 tasks, 2 profiles → one task gets dummy column (-1)
        double[][] cost = {
                {4, 2},
                {3, 5},
                {8, 1}
        };

        int[] result = HungarianAlgorithm.solve(cost);

        assertEquals(3, result.length);
        // At least 2 rows must have valid assignments
        int assigned = 0;
        for (int col : result) {
            if (col >= 0) assigned++;
        }
        assertEquals(2, assigned);

        // The two assigned should have minimum total cost
        double total = HungarianAlgorithm.totalCost(cost, result);
        // Optimal: row0→col1(2), row2→col1(1) conflicts, so row0→col0(4)+row2→col1(1)=5
        // or row1→col0(3)+row2→col1(1)=4
        assertTrue(total <= 5.0);
    }

    @Test
    void rectangularMoreColsThanRows_padsDummyRows() {
        // 2 tasks, 4 profiles
        double[][] cost = {
                {7, 3, 5, 9},
                {2, 8, 1, 4}
        };

        int[] result = HungarianAlgorithm.solve(cost);

        assertEquals(2, result.length);
        assertTrue(result[0] >= 0 && result[0] < 4);
        assertTrue(result[1] >= 0 && result[1] < 4);
        assertNotEquals(result[0], result[1]);

        double total = HungarianAlgorithm.totalCost(cost, result);
        // Optimal: row0→col1(3), row1→col2(1) → total = 4
        assertEquals(4.0, total, 0.001);
    }

    @Test
    void singleElement_trivial() {
        double[][] cost = {{42}};

        int[] result = HungarianAlgorithm.solve(cost);

        assertEquals(1, result.length);
        assertEquals(0, result[0]);
        assertEquals(42.0, HungarianAlgorithm.totalCost(cost, result), 0.001);
    }

    @Test
    void allZeroCosts_anyAssignmentOptimal() {
        double[][] cost = {
                {0, 0, 0},
                {0, 0, 0},
                {0, 0, 0}
        };

        int[] result = HungarianAlgorithm.solve(cost);

        assertEquals(3, result.length);
        assertEquals(0.0, HungarianAlgorithm.totalCost(cost, result), 0.001);
        // All columns must be distinct
        assertNotEquals(result[0], result[1]);
        assertNotEquals(result[0], result[2]);
        assertNotEquals(result[1], result[2]);
    }

    @Test
    void infinityCosts_excludesIncompatible() {
        // Row 0 can only go to col 0 or col 1 (col 2 = INF)
        // Row 1 can only go to col 2 (col 0 = INF, col 1 = INF)
        // Row 2 can go anywhere
        double[][] cost = {
                {  2,   3, INF},
                {INF, INF,   5},
                {  4,   1,   6}
        };

        int[] result = HungarianAlgorithm.solve(cost);
        double total = HungarianAlgorithm.totalCost(cost, result);

        // row1 must go to col2 (only option)
        assertEquals(2, result[1]);
        // Optimal: row0→col0(2), row1→col2(5), row2→col1(1) → total = 8
        assertEquals(8.0, total, 0.001);
    }

    @Test
    void largeMatrix_10x10_correctTotalCost() {
        // Diagonal matrix: cost[i][j] = |i - j| + 1 (identity assignment is optimal)
        int n = 10;
        double[][] cost = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                cost[i][j] = Math.abs(i - j) + 1;
            }
        }

        int[] result = HungarianAlgorithm.solve(cost);
        double total = HungarianAlgorithm.totalCost(cost, result);

        // Optimal is diagonal: each row assigned to same column, cost = 1 each → total = 10
        assertEquals(10.0, total, 0.001);
        for (int i = 0; i < n; i++) {
            assertEquals(i, result[i], "Row " + i + " should map to column " + i);
        }
    }

    @Test
    void entireRowInfinity_gracefulResult() {
        // Row 1 has no compatible column — all INF
        double[][] cost = {
                {2, 3},
                {INF, INF}
        };

        // Algorithm will still produce a result — row 1 gets a dummy column or
        // is forced to pick an INF entry. The result should not throw.
        int[] result = HungarianAlgorithm.solve(cost);

        assertEquals(2, result.length);
        // Row 0 should get a valid assignment
        assertTrue(result[0] >= 0);
    }

    @Test
    void nullOrEmpty_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> HungarianAlgorithm.solve(null));
        assertThrows(IllegalArgumentException.class, () -> HungarianAlgorithm.solve(new double[0][0]));
    }
}
