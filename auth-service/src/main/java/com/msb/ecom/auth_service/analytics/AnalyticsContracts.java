package com.msb.ecom.auth_service.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AnalyticsContracts {
    private AnalyticsContracts() { }

    public enum RangePreset {
        TODAY, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, CUSTOM
    }

    public enum Availability {
        AVAILABLE, DEGRADED, UNAVAILABLE, RESTRICTED
    }

    public enum ComparisonState {
        VALUE, NEW, NOT_APPLICABLE
    }

    public enum MetricUnit {
        COUNT, PERCENT, MONEY, DURATION_SECONDS
    }

    public enum Granularity {
        HOUR, DAY, WEEK
    }

    public enum TrendMetric {
        REPORTS_SUBMITTED,
        ORDERS_CREATED,
        DISPUTES_OPENED,
        REFUNDS_SUCCEEDED,
        SUPPORT_TICKETS_CREATED,
        LISTINGS_CREATED
    }

    public enum DrillDownKey {
        UNASSIGNED_REPORTS,
        READY_FOR_INVESTIGATION_REPORTS,
        OPEN_CASES,
        UNASSIGNED_CASES,
        READY_FOR_ACTION_CASES,
        PENDING_APPEALS,
        OPEN_DISPUTES,
        UNASSIGNED_DISPUTES,
        FAILED_REFUNDS,
        PROCESSING_REFUNDS,
        OPEN_SUPPORT_TICKETS,
        UNASSIGNED_SUPPORT_TICKETS,
        WAITING_FOR_USER_SUPPORT_TICKETS,
        FAILED_JOBS,
        FAILED_OUTBOX_EVENTS,
        DEAD_LETTER_OUTBOX_EVENTS,
        RECONCILIATION_ISSUES,
        INVENTORY_ISSUES,
        SEARCH_INDEX_FAILURES,
        PENDING_BUSINESS_APPLICATIONS,
        UNASSIGNED_LISTING_CASES,
        CANCELLED_ORDERS,
        COMPLETED_ORDERS,
        FAILED_PAYMENTS,
        PENDING_GOVERNANCE_APPROVALS,
        ACTIVE_TEMPORARY_ELEVATIONS
    }

    public record AnalyticsRange(
            RangePreset preset,
            Instant from,
            Instant to,
            String timezone,
            boolean comparisonEnabled,
            Instant comparisonFrom,
            Instant comparisonTo) { }

    public record Capabilities(
            boolean financialAmounts,
            boolean operations,
            boolean governance) { }

    public record AnalyticsMetric(
            String key,
            String label,
            BigDecimal currentValue,
            BigDecimal previousValue,
            BigDecimal absoluteChange,
            BigDecimal percentageChange,
            ComparisonState comparisonState,
            MetricUnit unit,
            DrillDownKey drillDownKey) { }

    public record AnalyticsBreakdownItem(
            String key,
            String label,
            BigDecimal value,
            BigDecimal secondaryValue,
            DrillDownKey drillDownKey) { }

    public record AnalyticsBreakdown(
            String key,
            String label,
            MetricUnit unit,
            String primaryLabel,
            String secondaryLabel,
            List<AnalyticsBreakdownItem> items) {
        public AnalyticsBreakdown {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record AnalyticsSection(
            Availability status,
            String safeMessage,
            Instant dataAsOf,
            List<AnalyticsMetric> metrics,
            List<AnalyticsBreakdown> breakdowns) {
        public AnalyticsSection {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            breakdowns = breakdowns == null ? List.of() : List.copyOf(breakdowns);
        }

        public static AnalyticsSection unavailable(String message, Instant generatedAt) {
            return new AnalyticsSection(Availability.UNAVAILABLE, message, generatedAt,
                    List.of(), List.of());
        }

        public static AnalyticsSection restricted(String message, Instant generatedAt) {
            return new AnalyticsSection(Availability.RESTRICTED, message, generatedAt,
                    List.of(), List.of());
        }
    }

    public record AdminAnalyticsOverview(
            AnalyticsRange range,
            Capabilities capabilities,
            AnalyticsSection marketplace,
            AnalyticsSection moderation,
            AnalyticsSection trustAndSafety,
            AnalyticsSection commerce,
            AnalyticsSection support,
            AnalyticsSection catalog,
            AnalyticsSection operations,
            AnalyticsSection governance,
            Instant generatedAt) { }

    public record TrendPoint(Instant bucketStart, Instant bucketEnd, BigDecimal value) { }

    public record AnalyticsTrend(
            TrendMetric metric,
            String label,
            MetricUnit unit,
            AnalyticsRange range,
            Granularity granularity,
            Availability status,
            String safeMessage,
            List<TrendPoint> points,
            Instant generatedAt) {
        public AnalyticsTrend {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }
}
