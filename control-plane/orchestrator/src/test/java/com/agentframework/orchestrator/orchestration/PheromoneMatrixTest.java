package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.WorkerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link PheromoneMatrix} — ACO pheromone data structure.
 *
 * @see <a href="https://mitpress.mit.edu/9780262042192/">
 *     Dorigo &amp; Stützle (2004), Ant Colony Optimization</a>
 */
class PheromoneMatrixTest {

    @Test
    void newMatrix_allCellsInitialized() {
        PheromoneMatrix m = new PheromoneMatrix(1.5);

        // Every cell should be initialised to tau_0
        for (WorkerType from : WorkerType.values()) {
            for (WorkerType to : WorkerType.values()) {
                assertThat(m.get(from, to)).isEqualTo(1.5);
            }
        }
        assertThat(m.size()).isEqualTo(WorkerType.values().length);
    }

    @Test
    void deposit_incrementsCell() {
        PheromoneMatrix m = new PheromoneMatrix(1.0);

        m.deposit(WorkerType.BE, WorkerType.REVIEW, 0.5);

        assertThat(m.get(WorkerType.BE, WorkerType.REVIEW)).isEqualTo(1.5);
        // Other cells should be unchanged
        assertThat(m.get(WorkerType.BE, WorkerType.FE)).isEqualTo(1.0);
    }

    @Test
    void evaporate_decaysByRho() {
        PheromoneMatrix m = new PheromoneMatrix(2.0);

        m.evaporate(0.1); // tau = (1 - 0.1) * 2.0 = 1.8

        for (WorkerType from : WorkerType.values()) {
            for (WorkerType to : WorkerType.values()) {
                assertThat(m.get(from, to)).isCloseTo(1.8, within(1e-9));
            }
        }
    }

    @Test
    void transitionProbabilities_sumToOne() {
        PheromoneMatrix m = new PheromoneMatrix(1.0);
        // Make one edge stronger to break symmetry
        m.deposit(WorkerType.BE, WorkerType.FE, 3.0);

        Map<WorkerType, Double> probs = m.transitionProbabilities(WorkerType.BE, 1.0);

        double sum = probs.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(sum).isCloseTo(1.0, within(1e-9));

        // FE should have the highest probability (4.0 vs 1.0 for others)
        double pFe = probs.get(WorkerType.FE);
        double pOther = probs.get(WorkerType.DBA);
        assertThat(pFe).isGreaterThan(pOther);
    }

    @Test
    void suggestNext_returnsTopK_ordered() {
        PheromoneMatrix m = new PheromoneMatrix(1.0);
        // Create a clear ordering: FE > DBA > REVIEW
        m.deposit(WorkerType.BE, WorkerType.FE, 5.0);
        m.deposit(WorkerType.BE, WorkerType.DBA, 3.0);
        m.deposit(WorkerType.BE, WorkerType.REVIEW, 2.0);

        List<WorkerType> top3 = m.suggestNext(WorkerType.BE, 1.0, 3);

        assertThat(top3).hasSize(3);
        assertThat(top3.get(0)).isEqualTo(WorkerType.FE);     // 6.0
        assertThat(top3.get(1)).isEqualTo(WorkerType.DBA);    // 4.0
        assertThat(top3.get(2)).isEqualTo(WorkerType.REVIEW); // 3.0
    }

    @Test
    void flatArrayRoundTrip_preservesMatrix() {
        PheromoneMatrix m = new PheromoneMatrix(1.0);
        m.deposit(WorkerType.BE, WorkerType.FE, 2.0);
        m.deposit(WorkerType.DBA, WorkerType.REVIEW, 1.5);

        double[] flat = m.toFlatArray();
        PheromoneMatrix restored = PheromoneMatrix.fromFlatArray(flat, 1.0);

        assertThat(restored.get(WorkerType.BE, WorkerType.FE)).isEqualTo(3.0);
        assertThat(restored.get(WorkerType.DBA, WorkerType.REVIEW)).isEqualTo(2.5);
        assertThat(restored.get(WorkerType.BE, WorkerType.DBA)).isEqualTo(1.0);
    }

    @Test
    void fromFlatArray_dimensionMismatch_returnsFreshMatrix() {
        // Simulate a WorkerType enum change — flat array with wrong size
        double[] wrongSize = new double[4]; // too small

        PheromoneMatrix m = PheromoneMatrix.fromFlatArray(wrongSize, 2.0);

        // Should get a fresh matrix with initialPheromone
        assertThat(m.get(WorkerType.BE, WorkerType.FE)).isEqualTo(2.0);
    }

    @Test
    void reset_restoresInitialPheromone() {
        PheromoneMatrix m = new PheromoneMatrix(1.0);
        m.deposit(WorkerType.BE, WorkerType.FE, 5.0);

        m.reset();

        assertThat(m.get(WorkerType.BE, WorkerType.FE)).isEqualTo(1.0);
    }
}
