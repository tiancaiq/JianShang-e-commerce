package com.msb.ecom.auth_service.analytics;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.*;

@Component
public class AnalyticsComparisonCalculator {

    public AnalyticsMetric count(String key, String label, Number current, Number previous,
            DrillDownKey drillDownKey) {
        return metric(key, label, decimal(current), decimal(previous), MetricUnit.COUNT,
                drillDownKey);
    }

    public AnalyticsMetric duration(String key, String label, Number current, Number previous,
            DrillDownKey drillDownKey) {
        return metric(key, label, decimal(current), decimal(previous),
                MetricUnit.DURATION_SECONDS, drillDownKey);
    }

    public AnalyticsMetric money(String key, String label, BigDecimal current,
            BigDecimal previous, DrillDownKey drillDownKey) {
        return metric(key, label, current, previous, MetricUnit.MONEY, drillDownKey);
    }

    public AnalyticsMetric percent(String key, String label, BigDecimal current,
            BigDecimal previous, DrillDownKey drillDownKey) {
        return metric(key, label, current, previous, MetricUnit.PERCENT, drillDownKey);
    }

    public AnalyticsMetric metric(String key, String label, BigDecimal current,
            BigDecimal previous, MetricUnit unit, DrillDownKey drillDownKey) {
        if (current == null) {
            return new AnalyticsMetric(key, label, null, previous, null, null,
                    ComparisonState.NOT_APPLICABLE, unit, drillDownKey);
        }
        BigDecimal safeCurrent = current;
        if (previous == null) {
            return new AnalyticsMetric(key, label, safeCurrent, null, null, null,
                    ComparisonState.NOT_APPLICABLE, unit, drillDownKey);
        }
        BigDecimal change = safeCurrent.subtract(previous);
        if (previous.compareTo(BigDecimal.ZERO) == 0) {
            ComparisonState state = safeCurrent.compareTo(BigDecimal.ZERO) > 0
                    ? ComparisonState.NEW : ComparisonState.NOT_APPLICABLE;
            return new AnalyticsMetric(key, label, safeCurrent, previous, change, null,
                    state, unit, drillDownKey);
        }
        BigDecimal percentage = change.multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 2, RoundingMode.HALF_UP);
        return new AnalyticsMetric(key, label, safeCurrent, previous, change, percentage,
                ComparisonState.VALUE, unit, drillDownKey);
    }

    private BigDecimal decimal(Number value) {
        return value == null ? null : new BigDecimal(value.toString());
    }
}
