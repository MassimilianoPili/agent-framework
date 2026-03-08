package com.agentframework.orchestrator.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link CompressedSensingRetriever}.
 *
 * <p>Covers OMP correctness on 1-sparse and 2-sparse signals, reconstruction quality,
 * convergence, input validation, and edge cases.</p>
 */
class CompressedSensingRetrieverTest {

    private CompressedSensingRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new CompressedSensingRetriever();
    }

    // ── Input validation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("throws on null or empty query")
    void reconstruct_emptyQuery_throws() {
        List<double[]> dict = List.of(new double[]{1.0, 0.0});
        assertThatThrownBy(() -> retriever.reconstruct(null, dict, List.of("A"), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> retriever.reconstruct(new double[]{}, dict, List.of("A"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("throws on empty dictionary")
    void reconstruct_emptyDictionary_throws() {
        assertThatThrownBy(() -> retriever.reconstruct(new double[]{1.0}, List.of(), null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("throws on atom dimension mismatch")
    void reconstruct_dimensionMismatch_throws() {
        // query is 2D but atom is 3D
        double[] query = {1.0, 0.0};
        List<double[]> dict = List.of(new double[]{1.0, 0.0, 0.0});
        assertThatThrownBy(() -> retriever.reconstruct(query, dict, List.of("A"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 1-sparse signal recovery ────────────────────────────────────────────────

    @Test
    @DisplayName("query aligned with atom A: OMP selects only A with quality ≈ 1.0")
    void reconstruct_oneSparseQuery_perfectRecovery() {
        // 2D dictionary: A = (1, 0), B = (0, 1)
        // query = 3·A = (3, 0) — 1-sparse in A
        double[] query = {3.0, 0.0};
        List<double[]> dict = List.of(new double[]{1.0, 0.0}, new double[]{0.0, 1.0});

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, List.of("BACKEND", "FRONTEND"), 2);

        assertThat(report).isNotNull();
        assertThat(report.selectedAtoms()).containsExactly("BACKEND");
        assertThat(report.sparsity()).isEqualTo(1);
        assertThat(report.reconstructionQuality()).isCloseTo(1.0, within(1e-9));
        assertThat(report.relativeResidualEnergy()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("query aligned with atom B: OMP selects only B")
    void reconstruct_oneSparseQueryB_selectsB() {
        double[] query = {0.0, 5.0};
        List<double[]> dict = List.of(new double[]{1.0, 0.0}, new double[]{0.0, 1.0});

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, List.of("BACKEND", "FRONTEND"), 2);

        assertThat(report.selectedAtoms()).containsExactly("FRONTEND");
        assertThat(report.reconstructionQuality()).isCloseTo(1.0, within(1e-9));
    }

    // ── 2-sparse signal recovery ────────────────────────────────────────────────

    @Test
    @DisplayName("2-sparse query: OMP selects exactly 2 atoms with near-perfect reconstruction")
    void reconstruct_twoSparseQuery_twoAtomsSelected() {
        // 3D dictionary: A=(1,0,0), B=(0,1,0), C=(0,0,1)
        // query = 2·A + 3·B = (2, 3, 0) — 2-sparse in {A, B}
        double[] query = {2.0, 3.0, 0.0};
        List<double[]> dict = List.of(
                new double[]{1.0, 0.0, 0.0},
                new double[]{0.0, 1.0, 0.0},
                new double[]{0.0, 0.0, 1.0}
        );

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, List.of("A", "B", "C"), 3);

        assertThat(report.selectedAtoms()).containsExactlyInAnyOrder("A", "B");
        assertThat(report.sparsity()).isEqualTo(2);
        assertThat(report.reconstructionQuality()).isCloseTo(1.0, within(1e-6));
    }

    // ── Atom labels ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("atom coefficients map contains positive coefficient for selected atom")
    void reconstruct_coefficient_positive() {
        double[] query = {1.0, 0.0};
        List<double[]> dict = List.of(new double[]{1.0, 0.0}, new double[]{0.0, 1.0});

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, List.of("BE", "FE"), 2);

        assertThat(report.atomCoefficients()).containsKey("BE");
        assertThat(report.atomCoefficients().get("BE")).isGreaterThan(0.0);
    }

    // ── maxAtoms constraint ─────────────────────────────────────────────────────

    @Test
    @DisplayName("maxAtoms=1 limits sparsity to 1 even when 2-sparse signal is used")
    void reconstruct_maxAtoms1_limitedSparsity() {
        double[] query = {2.0, 3.0, 0.0};
        List<double[]> dict = List.of(
                new double[]{1.0, 0.0, 0.0},
                new double[]{0.0, 1.0, 0.0},
                new double[]{0.0, 0.0, 1.0}
        );

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, List.of("A", "B", "C"), 1);

        assertThat(report.sparsity()).isEqualTo(1);
        // Reconstruction quality < 1.0 (can't represent 2-sparse with 1 atom)
        assertThat(report.reconstructionQuality()).isLessThan(1.0);
    }

    // ── Reconstruction quality bounds ───────────────────────────────────────────

    @Test
    @DisplayName("reconstructionQuality is in [0, 1]")
    void reconstruct_quality_bounded() {
        double[] query = {1.0, 1.0};
        List<double[]> dict = List.of(new double[]{1.0, 0.0}, new double[]{0.0, 1.0});

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, List.of("X", "Y"), 2);

        assertThat(report.reconstructionQuality()).isBetween(0.0, 1.0);
        assertThat(report.relativeResidualEnergy()).isBetween(0.0, 1.0);
    }

    // ── No-label fallback ───────────────────────────────────────────────────────

    @Test
    @DisplayName("null atomLabels uses atom_0, atom_1, ... naming")
    void reconstruct_noLabels_defaultNaming() {
        double[] query = {1.0, 0.0};
        List<double[]> dict = List.of(new double[]{1.0, 0.0}, new double[]{0.0, 1.0});

        CompressedSensingRetriever.SparsityReport report =
                retriever.reconstruct(query, dict, null, 2);

        assertThat(report.selectedAtoms().get(0)).startsWith("atom_");
    }
}
