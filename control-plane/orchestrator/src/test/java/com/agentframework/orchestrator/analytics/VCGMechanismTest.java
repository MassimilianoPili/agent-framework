package com.agentframework.orchestrator.analytics;

import com.agentframework.orchestrator.analytics.VCGMechanism.VCGResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link VCGMechanism}.
 *
 * <p>Verifies single-item Vickrey auction (second-price), multi-item Clarke
 * pivot payments, and economic properties (non-negative information rent).</p>
 */
class VCGMechanismTest {

    @Test
    @DisplayName("allocate selects bidder with highest valuation")
    void allocate_multipleBids_returnsHighestBidder() {
        double[] bids = {0.3, 0.8, 0.5};
        int winner = VCGMechanism.allocate(bids);
        assertThat(winner).isEqualTo(1);
    }

    @Test
    @DisplayName("VCG payment equals second-highest bid (Vickrey auction)")
    void vcgPayment_secondPrice_returnsSecondHighestBid() {
        double[] bids = {0.3, 0.8, 0.5};
        int winner = VCGMechanism.allocate(bids);
        double payment = VCGMechanism.vcgPayment(bids, winner);

        // Winner bid = 0.8, second-highest = 0.5
        assertThat(payment).isEqualTo(0.5);
    }

    @Test
    @DisplayName("VCG payment for single bidder is zero (no competition)")
    void vcgPayment_singleBidder_returnsZero() {
        double[] bids = {0.7};
        double payment = VCGMechanism.vcgPayment(bids, 0);
        assertThat(payment).isEqualTo(0.0);
    }

    @Test
    @DisplayName("compute with tied bids selects first bidder deterministically")
    void compute_tiedBids_returnsDeterministicWinner() {
        String[] names = {"alpha", "beta", "gamma"};
        double[] bids = {0.6, 0.6, 0.6};

        VCGResult result = VCGMechanism.compute(names, bids);

        // Tie-break: first bidder wins
        assertThat(result.winner()).isEqualTo("alpha");
        assertThat(result.winnerIndex()).isEqualTo(0);
        // Payment = second-highest = 0.6 (same as winner)
        assertThat(result.payment()).isEqualTo(0.6);
        // Information rent = 0 when tied
        assertThat(result.informationRent()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("social welfare sums assigned valuations")
    void socialWelfare_validAllocation_returnsSumOfAssignedValues() {
        // 3 bidders, 2 items
        double[][] valuations = {
                {0.9, 0.3},  // bidder 0
                {0.4, 0.8},  // bidder 1
                {0.5, 0.6}   // bidder 2
        };
        // Allocation: item 0 → bidder 0, item 1 → bidder 1
        int[] allocation = {0, 1};

        double welfare = VCGMechanism.socialWelfare(valuations, allocation);

        // 0.9 (bidder 0, item 0) + 0.8 (bidder 1, item 1)
        assertThat(welfare).isCloseTo(1.7, within(1e-9));
    }

    @Test
    @DisplayName("Clarke payments charge each winner their externality on others")
    void clarkePayments_multiItem_computesCorrectExternality() {
        // 3 bidders, 2 items
        double[][] valuations = {
                {0.9, 0.3},  // bidder 0
                {0.4, 0.8},  // bidder 1
                {0.5, 0.6}   // bidder 2
        };
        // Optimal allocation: item 0 → bidder 0 (0.9), item 1 → bidder 1 (0.8)
        int[] allocation = VCGMechanism.multiItemAllocate(valuations);

        assertThat(allocation[0]).isEqualTo(0); // bidder 0 gets item 0
        assertThat(allocation[1]).isEqualTo(1); // bidder 1 gets item 1

        double[] payments = VCGMechanism.clarkePayments(valuations, allocation);

        // Bidder 0 payment:
        //   Others in current = bidder 1 gets 0.8
        //   Optimal without bidder 0: bidder 1→item 0 (0.4) or item 1 (0.8), bidder 2→other
        //   Without bidder 0: bidder 1 gets item 1 (0.8), bidder 2 gets item 0 (0.5) → total 1.3
        //   payment_0 = 1.3 - 0.8 = 0.5
        assertThat(payments[0]).isCloseTo(0.5, within(1e-9));

        // Bidder 1 payment:
        //   Others in current = bidder 0 gets 0.9
        //   Without bidder 1: bidder 0 gets item 0 (0.9), bidder 2 gets item 1 (0.6) → total 1.5
        //   payment_1 = 1.5 - 0.9 = 0.6
        assertThat(payments[1]).isCloseTo(0.6, within(1e-9));

        // Bidder 2: not allocated → payment = 0
        assertThat(payments[2]).isEqualTo(0.0);
    }

    @Test
    @DisplayName("information rent (bid - payment) is non-negative for winner")
    void informationRent_secondPrice_isNonNegative() {
        String[] names = {"a", "b", "c", "d"};
        double[] bids = {0.2, 0.9, 0.7, 0.4};

        VCGResult result = VCGMechanism.compute(names, bids);

        assertThat(result.winner()).isEqualTo("b");
        assertThat(result.informationRent()).isGreaterThanOrEqualTo(0.0);
        // rent = 0.9 - 0.7 = 0.2
        assertThat(result.informationRent()).isCloseTo(0.2, within(1e-9));
    }
}
