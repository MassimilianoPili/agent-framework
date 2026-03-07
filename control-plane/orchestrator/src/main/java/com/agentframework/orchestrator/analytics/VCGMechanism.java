package com.agentframework.orchestrator.analytics;

/**
 * Vickrey-Clarke-Groves (VCG) mechanism for truthful task pricing.
 *
 * <p>The VCG mechanism is a generalization of the Vickrey (second-price) auction
 * to multi-item settings. It guarantees <em>truthful revelation</em> as a dominant
 * strategy: each bidder maximizes their utility by bidding their true valuation.</p>
 *
 * <p>Single-item case (Vickrey auction): the winner pays the second-highest bid.
 * Multi-item case (Clarke pivot): each winner pays their externality on the other
 * bidders — the reduction in social welfare caused by their presence.</p>
 *
 * <p>In the agent framework context, worker profiles "bid" their expected reward
 * (from GP predictions or historical performance), and VCG determines both the
 * optimal allocation and the fair price for each task.</p>
 *
 * @see <a href="https://doi.org/10.2307/2977633">
 *     Vickrey (1961), Counterspeculation, Auctions, and Competitive Sealed Tenders,
 *     Journal of Finance 16(1)</a>
 * @see <a href="https://doi.org/10.1007/BF01726210">
 *     Clarke (1971), Multipart Pricing of Public Goods, Public Choice 11(1)</a>
 * @see <a href="https://doi.org/10.2307/1914085">
 *     Groves (1973), Incentives in Teams, Econometrica 41(4)</a>
 */
public final class VCGMechanism {

    private VCGMechanism() {}

    /**
     * Single-item allocation: selects the bidder with the highest valuation.
     *
     * <p>Tie-break: first bidder wins (deterministic, stable).</p>
     *
     * @param bids valuations, one per bidder
     * @return index of the winning bidder
     * @throws IllegalArgumentException if bids is empty
     */
    static int allocate(double[] bids) {
        if (bids.length == 0) {
            throw new IllegalArgumentException("Cannot allocate with zero bids");
        }
        int best = 0;
        for (int i = 1; i < bids.length; i++) {
            if (bids[i] > bids[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * VCG payment (second-price / Vickrey auction).
     *
     * <p>The winner pays the highest bid among all other bidders. This ensures
     * truthful bidding is a dominant strategy.</p>
     *
     * <p>Single bidder: payment = 0 (no competition, no externality).</p>
     *
     * @param bids        valuations, one per bidder
     * @param winnerIndex index of the allocated winner
     * @return payment = max(bids excluding winner), or 0 if single bidder
     */
    static double vcgPayment(double[] bids, int winnerIndex) {
        if (bids.length <= 1) {
            return 0.0;
        }
        double maxOther = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < bids.length; i++) {
            if (i != winnerIndex && bids[i] > maxOther) {
                maxOther = bids[i];
            }
        }
        return maxOther;
    }

    /**
     * Clarke pivot payments for multi-item allocation.
     *
     * <p>For each allocated bidder i:<br>
     * {@code payment_i = socialWelfare(optimal without i) - sum(others' values in current allocation)}</p>
     *
     * <p>This charges each winner the externality they impose on the other bidders
     * by their presence in the auction.</p>
     *
     * @param valuations  2D array: valuations[bidder][item]
     * @param allocation  allocation[item] = bidder index assigned to item (-1 if unassigned)
     * @return payments array, one per bidder (only allocated bidders pay &gt; 0)
     */
    static double[] clarkePayments(double[][] valuations, int[] allocation) {
        int numBidders = valuations.length;
        int numItems = allocation.length;
        double[] payments = new double[numBidders];

        // Current social welfare for "others" (for each bidder i, sum of values of everyone else)
        for (int i = 0; i < numBidders; i++) {
            // Check if bidder i is allocated any item
            boolean isAllocated = false;
            for (int j = 0; j < numItems; j++) {
                if (allocation[j] == i) {
                    isAllocated = true;
                    break;
                }
            }
            if (!isAllocated) {
                payments[i] = 0.0;
                continue;
            }

            // Sum of others' values in current allocation
            double othersValueCurrent = 0.0;
            for (int j = 0; j < numItems; j++) {
                if (allocation[j] >= 0 && allocation[j] != i) {
                    othersValueCurrent += valuations[allocation[j]][j];
                }
            }

            // Optimal allocation without bidder i
            double[][] reducedValuations = new double[numBidders][numItems];
            for (int b = 0; b < numBidders; b++) {
                if (b == i) {
                    // Set bidder i's valuations to negative infinity to exclude them
                    for (int j = 0; j < numItems; j++) {
                        reducedValuations[b][j] = Double.NEGATIVE_INFINITY;
                    }
                } else {
                    System.arraycopy(valuations[b], 0, reducedValuations[b], 0, numItems);
                }
            }
            int[] optimalWithoutI = multiItemAllocate(reducedValuations);
            double othersValueOptimal = 0.0;
            for (int j = 0; j < numItems; j++) {
                if (optimalWithoutI[j] >= 0 && optimalWithoutI[j] != i) {
                    othersValueOptimal += valuations[optimalWithoutI[j]][j];
                }
            }

            payments[i] = othersValueOptimal - othersValueCurrent;
        }

        return payments;
    }

    /**
     * Social welfare of a multi-item allocation.
     *
     * @param valuations  2D array: valuations[bidder][item]
     * @param allocation  allocation[item] = bidder index (-1 if unassigned)
     * @return sum of assigned valuations
     */
    static double socialWelfare(double[][] valuations, int[] allocation) {
        double total = 0.0;
        for (int j = 0; j < allocation.length; j++) {
            if (allocation[j] >= 0) {
                total += valuations[allocation[j]][j];
            }
        }
        return total;
    }

    /**
     * Greedy multi-item allocation.
     *
     * <p>Assigns each item to the bidder with the highest valuation, subject to
     * a one-item-per-bidder constraint. Items are processed in order of their
     * highest available bid (descending).</p>
     *
     * @param valuations valuations[bidder][item]
     * @return allocation[item] = bidder index, or -1 if unassigned
     */
    static int[] multiItemAllocate(double[][] valuations) {
        int numBidders = valuations.length;
        int numItems = valuations[0].length;
        int[] allocation = new int[numItems];
        boolean[] bidderUsed = new boolean[numBidders];
        boolean[] itemAssigned = new boolean[numItems];

        for (int j = 0; j < numItems; j++) {
            allocation[j] = -1;
        }

        // Greedy: assign items by highest available bid
        for (int round = 0; round < Math.min(numBidders, numItems); round++) {
            double bestVal = Double.NEGATIVE_INFINITY;
            int bestBidder = -1;
            int bestItem = -1;

            for (int b = 0; b < numBidders; b++) {
                if (bidderUsed[b]) continue;
                for (int j = 0; j < numItems; j++) {
                    if (itemAssigned[j]) continue;
                    if (valuations[b][j] > bestVal) {
                        bestVal = valuations[b][j];
                        bestBidder = b;
                        bestItem = j;
                    }
                }
            }

            if (bestBidder < 0) break;

            allocation[bestItem] = bestBidder;
            bidderUsed[bestBidder] = true;
            itemAssigned[bestItem] = true;
        }

        return allocation;
    }

    /**
     * Complete single-item VCG computation.
     *
     * @param bidderNames identifiers for each bidder
     * @param bids        valuation per bidder
     * @return VCGResult with winner, payment, social welfare, and information rent
     */
    static VCGResult compute(String[] bidderNames, double[] bids) {
        int winnerIndex = allocate(bids);
        double payment = vcgPayment(bids, winnerIndex);
        double welfare = bids[winnerIndex]; // single-item: welfare = winner's value
        double rent = bids[winnerIndex] - payment;

        return new VCGResult(
                bidderNames[winnerIndex],
                winnerIndex,
                bids[winnerIndex],
                payment,
                welfare,
                rent
        );
    }

    /**
     * Result of a single-item VCG auction.
     *
     * @param winner          name of the winning bidder
     * @param winnerIndex     index of the winner in the bids array
     * @param winnerBid       the winner's valuation
     * @param payment         second-price payment (highest bid among others)
     * @param socialWelfare   total welfare of the allocation (= winnerBid for single-item)
     * @param informationRent surplus retained by the winner (winnerBid - payment)
     */
    public record VCGResult(
            String winner,
            int winnerIndex,
            double winnerBid,
            double payment,
            double socialWelfare,
            double informationRent
    ) {}

    /**
     * Result of a multi-item VCG auction.
     *
     * @param allocation        allocation[item] = bidder index (-1 if unassigned)
     * @param payments          Clarke pivot payment per bidder
     * @param totalSocialWelfare sum of assigned valuations
     * @param bidderNames       names of bidders
     */
    public record MultiItemVCGResult(
            int[] allocation,
            double[] payments,
            double totalSocialWelfare,
            String[] bidderNames
    ) {}
}
