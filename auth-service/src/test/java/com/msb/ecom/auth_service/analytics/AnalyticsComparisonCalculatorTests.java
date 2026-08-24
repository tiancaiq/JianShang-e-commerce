package com.msb.ecom.auth_service.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.ComparisonState;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsComparisonCalculatorTests {
    private final AnalyticsComparisonCalculator calculator = new AnalyticsComparisonCalculator();

    @Test
    void calculatesIncreaseAndDecreaseWithoutInfinity() {
        var increase = calculator.count("orders", "Orders", 12, 8, null);
        var decrease = calculator.count("reports", "Reports", 5, 10, null);

        assertThat(increase.absoluteChange()).isEqualByComparingTo("4");
        assertThat(increase.percentageChange()).isEqualByComparingTo("50.00");
        assertThat(decrease.absoluteChange()).isEqualByComparingTo("-5");
        assertThat(decrease.percentageChange()).isEqualByComparingTo("-50.00");
    }

    @Test
    void representsZeroDenominatorsAsNewOrNotApplicable() {
        var created = calculator.count("created", "Created", 3, 0, null);
        var bothZero = calculator.count("zero", "Zero", 0, 0, null);

        assertThat(created.comparisonState()).isEqualTo(ComparisonState.NEW);
        assertThat(created.percentageChange()).isNull();
        assertThat(bothZero.comparisonState()).isEqualTo(ComparisonState.NOT_APPLICABLE);
        assertThat(bothZero.percentageChange()).isNull();
    }

    @Test
    void backlogWithoutHistoricalSnapshotIsNotCompared() {
        var backlog = calculator.metric("open", "Open", BigDecimal.TEN, null,
                AnalyticsContracts.MetricUnit.COUNT, null);

        assertThat(backlog.previousValue()).isNull();
        assertThat(backlog.comparisonState()).isEqualTo(ComparisonState.NOT_APPLICABLE);
    }
}
