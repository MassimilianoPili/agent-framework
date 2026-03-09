package com.agentframework.common.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link PrivacyBudget} (#43 — Differential Privacy).
 */
@DisplayName("PrivacyBudget (#43) — Daily DP budget tracker")
class PrivacyBudgetTest {

    @Test
    @DisplayName("canQuery within budget returns true")
    void canQuery_withinBudget_returnsTrue() {
        PrivacyBudget budget = new PrivacyBudget(5);

        assertThat(budget.canQuery()).isTrue();
        assertThat(budget.remaining()).isEqualTo(5);
        assertThat(budget.queriesUsedToday()).isZero();
    }

    @Test
    @DisplayName("canQuery after budget exhausted returns false")
    void canQuery_budgetExhausted_returnsFalse() {
        PrivacyBudget budget = new PrivacyBudget(3);

        budget.recordQuery();
        budget.recordQuery();
        budget.recordQuery();

        assertThat(budget.canQuery()).isFalse();
        assertThat(budget.remaining()).isZero();
        assertThat(budget.queriesUsedToday()).isEqualTo(3);
    }

    @Test
    @DisplayName("recordQuery decrements remaining budget")
    void recordQuery_decrementsRemaining() {
        PrivacyBudget budget = new PrivacyBudget(10);

        budget.recordQuery();
        assertThat(budget.remaining()).isEqualTo(9);
        assertThat(budget.queriesUsedToday()).isEqualTo(1);

        budget.recordQuery();
        budget.recordQuery();
        assertThat(budget.remaining()).isEqualTo(7);
        assertThat(budget.queriesUsedToday()).isEqualTo(3);
    }

    @Test
    @DisplayName("maxQueriesPerDay returns the configured limit")
    void maxQueriesPerDay_returnsConfiguredLimit() {
        PrivacyBudget budget = new PrivacyBudget(42);
        assertThat(budget.maxQueriesPerDay()).isEqualTo(42);
    }

    @Test
    @DisplayName("invalid maxQueriesPerDay throws")
    void constructor_invalidMax_throws() {
        assertThatThrownBy(() -> new PrivacyBudget(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrivacyBudget(-5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("remaining never goes negative even with excess recordQuery calls")
    void remaining_neverNegative() {
        PrivacyBudget budget = new PrivacyBudget(2);

        budget.recordQuery();
        budget.recordQuery();
        budget.recordQuery(); // over-recording
        budget.recordQuery();

        assertThat(budget.remaining()).isZero();
        assertThat(budget.queriesUsedToday()).isEqualTo(4); // records but remaining clamped
    }
}
