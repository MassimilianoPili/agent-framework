package com.agentframework.orchestrator.graph;

import org.junit.jupiter.api.Test;

import static com.agentframework.orchestrator.graph.TropicalSemiring.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link TropicalSemiring} — pure tropical (min-plus) algebra.
 */
class TropicalSemiringTest {

    @Test
    void add_returnsMin() {
        assertThat(add(3.0, 5.0)).isEqualTo(3.0);
        assertThat(add(5.0, 3.0)).isEqualTo(3.0);
        assertThat(add(INF, 3.0)).isEqualTo(3.0);
        assertThat(add(3.0, INF)).isEqualTo(3.0);
        assertThat(add(INF, INF)).isEqualTo(INF);
    }

    @Test
    void multiply_addValues_infHandling() {
        assertThat(multiply(3.0, 5.0)).isEqualTo(8.0);
        assertThat(multiply(ONE, 5.0)).isEqualTo(5.0);
        assertThat(multiply(5.0, ONE)).isEqualTo(5.0);
        assertThat(multiply(INF, 5.0)).isEqualTo(INF);
        assertThat(multiply(5.0, INF)).isEqualTo(INF);
    }

    @Test
    void matMul_twoByTwo_correctResult() {
        // A = [[2, INF], [1, 3]]
        // B = [[INF, 4], [5, 1]]
        // C[0][0] = min(2+INF, INF+5) = INF
        // C[0][1] = min(2+4, INF+1) = 6
        // C[1][0] = min(1+INF, 3+5) = 8
        // C[1][1] = min(1+4, 3+1) = 4
        double[][] a = {{2, INF}, {1, 3}};
        double[][] b = {{INF, 4}, {5, 1}};

        double[][] c = matMul(a, b);

        assertThat(c[0][0]).isEqualTo(INF);
        assertThat(c[0][1]).isCloseTo(6.0, within(1e-9));
        assertThat(c[1][0]).isCloseTo(8.0, within(1e-9));
        assertThat(c[1][1]).isCloseTo(4.0, within(1e-9));
    }
}
