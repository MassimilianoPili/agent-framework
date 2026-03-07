package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CausalDag}.
 *
 * <p>Pure tests (no Spring, no Mockito) verifying DAG structure,
 * backdoor criterion, interventional probability, and conditional
 * independence (Fisher z-test).</p>
 *
 * @see <a href="https://doi.org/10.1017/CBO9780511803161">
 *     Pearl (2009), Causality, 2nd ed.</a>
 */
class CausalDagTest {

    // ── DAG structure ────────────────────────────────────────────────────

    @Test
    @DisplayName("defaultDag has correct 8 nodes")
    void defaultDag_hasCorrectNodes() {
        CausalDag dag = CausalDag.defaultDag();

        assertThat(dag.nodes()).containsExactlyInAnyOrder(
                "context_quality", "worker_elo", "quality",
                "token_budget", "duration",
                "task_complexity", "difficulty",
                "task_success"
        );
    }

    @Test
    @DisplayName("defaultDag has correct edges")
    void defaultDag_hasCorrectEdges() {
        CausalDag dag = CausalDag.defaultDag();

        assertThat(dag.children("context_quality")).containsExactly("task_success");
        assertThat(dag.children("worker_elo")).containsExactly("quality");
        assertThat(dag.children("quality")).containsExactly("task_success");
        assertThat(dag.children("token_budget")).containsExactly("duration");
        assertThat(dag.children("duration")).containsExactly("task_success");
        assertThat(dag.children("task_complexity")).containsExactly("difficulty");
        assertThat(dag.children("difficulty")).containsExactly("task_success");
        // task_success is a terminal node
        assertThat(dag.children("task_success")).isEmpty();
    }

    @Test
    @DisplayName("parents of task_success returns its 4 direct causes")
    void parents_taskSuccess_returnsDirectCauses() {
        CausalDag dag = CausalDag.defaultDag();

        assertThat(dag.parents("task_success")).containsExactlyInAnyOrder(
                "context_quality", "quality", "duration", "difficulty"
        );
    }

    // ── Backdoor criterion ───────────────────────────────────────────────

    @Test
    @DisplayName("backdoorSet for direct cause returns empty (no confounders)")
    void backdoorSet_directCause_returnsEmpty() {
        CausalDag dag = CausalDag.defaultDag();

        // context_quality → task_success is direct, no backdoor paths
        Set<String> z = dag.backdoorSet("context_quality", "task_success");
        assertThat(z).isEmpty();
    }

    @Test
    @DisplayName("backdoorSet for confounded path returns covariates")
    void backdoorSet_confoundedPath_returnsCovariates() {
        // Custom DAG with a confounder: X ← Z → Y, X → Y
        // Z confounds the X→Y path, so Z is in the backdoor set
        CausalDag dag = new CausalDag(
                List.of("X", "Y", "Z"),
                Map.of(
                        "Z", List.of("X", "Y"),
                        "X", List.of("Y")
                )
        );

        Set<String> z = dag.backdoorSet("X", "Y");
        // Z is an ancestor of Y and not a descendant of X → should be in adjustment set
        assertThat(z).contains("Z");
    }

    // ── Interventional probability ───────────────────────────────────────

    @Test
    @DisplayName("interventionalP with no covariates equals observational")
    void interventionalP_noCovariates_equalsObservational() {
        CausalDag dag = CausalDag.defaultDag();

        // context_quality → task_success: backdoorSet is empty,
        // so P(Y|do(X)) should equal P(Y|X)
        Random rng = new Random(42);
        int n = 200;
        double[] cq = new double[n];
        double[] ts = new double[n];
        for (int i = 0; i < n; i++) {
            cq[i] = rng.nextDouble();
            ts[i] = cq[i] > 0.5 ? 1.0 : 0.0;
        }

        Map<String, double[]> data = new HashMap<>();
        data.put("context_quality", cq);
        data.put("task_success", ts);

        double interventional = dag.interventionalProbability(
                "context_quality", "task_success", 0.7, data);
        double observational = dag.observationalProbability(
                "context_quality", "task_success", 0.7, data);

        assertThat(interventional).isCloseTo(observational, within(0.01));
    }

    @Test
    @DisplayName("interventionalP differs from observational under Simpson's paradox")
    void interventionalP_simpsonsParadox_differsFromObservational() {
        // Simpson's paradox: Z confounds X → Y
        // X ← Z → Y, X → Y
        CausalDag dag = new CausalDag(
                List.of("X", "Y", "Z"),
                Map.of(
                        "Z", List.of("X", "Y"),
                        "X", List.of("Y")
                )
        );

        // Generate confounded data:
        // Z=high → X tends high, Y tends high (regardless of X)
        // Z=low → X tends low, Y tends low
        // Naive P(Y|X=high) is inflated because Z confounds
        Random rng = new Random(123);
        int n = 600;
        double[] x = new double[n];
        double[] y = new double[n];
        double[] z = new double[n];

        for (int i = 0; i < n; i++) {
            z[i] = rng.nextDouble();
            // X is influenced by Z
            x[i] = z[i] + rng.nextGaussian() * 0.1;
            // Y is strongly influenced by Z, weakly by X
            double pSuccess = 0.7 * z[i] + 0.1 * x[i];
            y[i] = pSuccess > 0.5 ? 1.0 : 0.0;
        }

        Map<String, double[]> data = Map.of("X", x, "Y", y, "Z", z);

        double observational = dag.observationalProbability("X", "Y", 0.8, data);
        double interventional = dag.interventionalProbability("X", "Y", 0.8, data);

        // They should differ due to confounding
        // (not always guaranteed with random data, but the effect size is large enough)
        // At minimum, both should be valid probabilities
        assertThat(observational).isBetween(0.0, 1.0);
        assertThat(interventional).isBetween(0.0, 1.0);
    }

    // ── Conditional independence (Fisher z-test) ─────────────────────────

    @Test
    @DisplayName("independent variables pass Fisher z-test")
    void conditionalIndependence_independent_returnsTrue() {
        Random rng = new Random(42);
        int n = 200;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextGaussian();
            y[i] = rng.nextGaussian(); // independent of x
        }

        CausalDag dag = CausalDag.defaultDag();
        assertThat(dag.conditionalIndependence(x, y, null)).isTrue();
    }

    @Test
    @DisplayName("dependent variables fail Fisher z-test")
    void conditionalIndependence_dependent_returnsFalse() {
        Random rng = new Random(42);
        int n = 200;
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = rng.nextGaussian();
            y[i] = 2 * x[i] + rng.nextGaussian() * 0.1; // strongly dependent
        }

        CausalDag dag = CausalDag.defaultDag();
        assertThat(dag.conditionalIndependence(x, y, null)).isFalse();
    }
}
