package com.agentframework.orchestrator.orchestration;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link InventoryTracker} — bid-ask spread inventory risk model.
 *
 * @see <a href="https://doi.org/10.1080/14697680802341587">
 *     Avellaneda &amp; Stoikov (2008), High-frequency trading in a limit order book</a>
 */
class InventoryTrackerTest {

    @Test
    void spread_atTarget_returnsBaseSpread() {
        InventoryTracker tracker = new InventoryTracker(
                Map.of("BE", 5),
                Map.of("BE", 5.0)
        );

        // inv == target → spread = BASE_SPREAD × (1 + 0/5) = BASE_SPREAD
        assertThat(tracker.spread("BE"))
                .isCloseTo(InventoryTracker.BASE_SPREAD, within(1e-9));
    }

    @Test
    void spread_aboveTarget_widens() {
        InventoryTracker tracker = new InventoryTracker(
                Map.of("BE", 10),
                Map.of("BE", 5.0)
        );

        // spread = BASE_SPREAD × (1 + |10-5|/5) = BASE_SPREAD × 2.0
        double expected = InventoryTracker.BASE_SPREAD * 2.0;
        assertThat(tracker.spread("BE")).isCloseTo(expected, within(1e-9));
    }

    @Test
    void spread_zeroTarget_safeDivision() {
        InventoryTracker tracker = new InventoryTracker(
                Map.of("BE", 3),
                Map.of("BE", 0.0)
        );

        // target=0 → max(target, 1)=1, spread = BASE_SPREAD × (1 + 3/1) = BASE_SPREAD × 4.0
        double expected = InventoryTracker.BASE_SPREAD * 4.0;
        assertThat(tracker.spread("BE")).isCloseTo(expected, within(1e-9));
    }

    @Test
    void priority_criticalPath_doubleBonus() {
        InventoryTracker tracker = new InventoryTracker(
                Map.of("BE", 5),
                Map.of("BE", 5.0)
        );

        double prioNormal = tracker.priority("BE", 1.0, false);
        double prioCP = tracker.priority("BE", 1.0, true);

        // cpBonus = 2.0 for critical path → priority should be double
        assertThat(prioCP).isCloseTo(prioNormal * 2.0, within(1e-9));
    }

    @Test
    void priority_longWait_higherUrgency() {
        InventoryTracker tracker = new InventoryTracker(
                Map.of("BE", 5),
                Map.of("BE", 5.0)
        );

        double prioShort = tracker.priority("BE", 0, false);
        double prioLong = tracker.priority("BE", 8.0, false);

        // urgency(8) = 1.0 + 8/4 = 3.0 vs urgency(0) = 1.0
        assertThat(prioLong).isGreaterThan(prioShort);
        assertThat(prioLong / prioShort).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void urgency_zeroHours_returnsOne() {
        assertThat(InventoryTracker.urgency(0)).isCloseTo(1.0, within(1e-9));
    }
}
