package com.msb.ecom.product_service.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ProductAnalyticsContracts {
    private ProductAnalyticsContracts() { }

    public enum Granularity { HOUR, DAY, WEEK }

    public record AnalyticsRange(Instant from, Instant to, String timezone) { }

    public record ListingStateSummary(
            long totalListings,
            long draftListings,
            long pendingReviewListings,
            long activeListings,
            long pausedListings,
            long soldListings,
            long closedListings,
            long rejectedListings,
            long changesRequestedListings,
            long removedByAdminListings,
            long newListings) { }

    public record ModerationSummary(
            long openCount,
            long unassignedCount,
            long assignedCount,
            Instant oldestOpenAt,
            Long oldestOpenAgeSeconds,
            long resolvedInRange,
            Long averageResolutionSeconds,
            long resolvedDecisionsInRange,
            long approvedInRange,
            long rejectedInRange,
            long changesRequestedInRange,
            BigDecimal rejectionRate) { }

    public record CategoryCount(String categoryId, String categoryName, long listingCount) { }

    public record CategorySummary(
            List<CategoryCount> topByCurrentListings,
            List<CategoryCount> topByNewListings) {
        public CategorySummary {
            topByCurrentListings = List.copyOf(topByCurrentListings);
            topByNewListings = List.copyOf(topByNewListings);
        }
    }

    public record EnforcementActionBreakdown(
            String actionType,
            long createdInRange,
            long currentActive) { }

    public record EnforcementScopeBreakdown(
            String scope,
            long createdInRange,
            long currentActive) { }

    public record ListingEnforcementSummary(
            String targetType,
            long createdInRange,
            long currentActive,
            List<EnforcementActionBreakdown> byActionType,
            List<EnforcementScopeBreakdown> byScope) {
        public ListingEnforcementSummary {
            byActionType = List.copyOf(byActionType);
            byScope = List.copyOf(byScope);
        }
    }

    public record Summary(
            AnalyticsRange range,
            ListingStateSummary listings,
            ModerationSummary moderation,
            CategorySummary categories,
            ListingEnforcementSummary listingEnforcement,
            Instant generatedAt) { }

    public record TrendPoint(Instant bucketStart, Instant bucketEnd, long value) { }

    public record ListingCreatedTrend(
            String metric,
            AnalyticsRange range,
            Granularity granularity,
            List<TrendPoint> points,
            long total,
            Instant generatedAt) {
        public ListingCreatedTrend {
            points = List.copyOf(points);
        }
    }
}
