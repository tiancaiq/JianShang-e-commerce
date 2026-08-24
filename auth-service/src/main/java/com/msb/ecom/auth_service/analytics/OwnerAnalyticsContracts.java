package com.msb.ecom.auth_service.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OwnerAnalyticsContracts {
    private OwnerAnalyticsContracts() { }

    public record OwnerRange(Instant from, Instant to, String timezone,
            Instant previousFrom, Instant previousTo) { }

    public record MoneyAmount(String currency, BigDecimal amount) { }

    public record OrderWindow(long ordersCreated, long ordersConfirmed,
            long currentCancelledOrders, long fullyDeliveredOrders,
            List<MoneyAmount> grossCreatedAmounts) { }
    public record DisputeWindow(long disputesOpened, long disputesResolved,
            long resolvedNoAction, long returnsApproved, long refundsRecommended,
            long partialRefundsRecommended) { }
    public record OrderOwnerWindow(OrderWindow orders, DisputeWindow disputes) { }
    public record DisputeBacklog(long openCount, long unassignedCount,
            Instant oldestOpenCreatedAt, Long oldestOpenAgeSeconds) { }
    public record OrderSummary(OwnerRange range, OrderOwnerWindow current,
            OrderOwnerWindow previous, DisputeBacklog currentDisputeBacklog,
            Instant generatedAt) { }

    public record PaymentOutcomeWindow(long paymentsSucceeded, long paymentsFailed,
            BigDecimal paymentSuccessRatePercent, List<MoneyAmount> succeededPaymentAmounts) { }
    public record RefundAmountMetric(String currency, BigDecimal requestedAmount,
            BigDecimal succeededAmount, BigDecimal failedAmount) { }
    public record RefundWindow(long refundsRequested, long refundsSucceeded, long refundsFailed,
            long fullRefundsRequested, long partialRefundsRequested, long fullRefundsSucceeded,
            long partialRefundsSucceeded, long fullRefundsFailed, long partialRefundsFailed,
            long returnRefundsSucceeded, List<RefundAmountMetric> amountMetrics) { }
    public record PaymentOwnerWindow(PaymentOutcomeWindow payments, RefundWindow refunds) { }
    public record RefundActiveAmount(String currency, BigDecimal amount) { }
    public record RefundBacklog(long pendingCount, long processingCount,
            Instant oldestActiveCreatedAt, Long oldestActiveAgeSeconds,
            List<RefundActiveAmount> activeAmounts) { }
    public record PaymentSummary(OwnerRange range, PaymentOwnerWindow current,
            PaymentOwnerWindow previous, RefundBacklog currentRefundBacklog,
            Instant generatedAt) { }

    public record ListingStateSummary(long totalListings, long draftListings,
            long pendingReviewListings, long activeListings, long pausedListings,
            long soldListings, long closedListings, long rejectedListings,
            long changesRequestedListings, long removedByAdminListings, long newListings) { }
    public record ProductModerationSummary(long openCount, long unassignedCount,
            long assignedCount, Instant oldestOpenAt, Long oldestOpenAgeSeconds,
            long resolvedInRange, Long averageResolutionSeconds,
            long resolvedDecisionsInRange, long approvedInRange, long rejectedInRange,
            long changesRequestedInRange, BigDecimal rejectionRate) { }
    public record CategoryCount(String categoryId, String categoryName, long listingCount) { }
    public record CategorySummary(List<CategoryCount> topByCurrentListings,
            List<CategoryCount> topByNewListings) { }
    public record EnforcementActionBreakdown(String actionType, long createdInRange,
            long currentActive) { }
    public record EnforcementScopeBreakdown(String scope, long createdInRange,
            long currentActive) { }
    public record ListingEnforcementSummary(String targetType, long createdInRange,
            long currentActive, List<EnforcementActionBreakdown> byActionType,
            List<EnforcementScopeBreakdown> byScope) { }
    public record ProductRange(Instant from, Instant to, String timezone) { }
    public record ProductSummary(ProductRange range, ListingStateSummary listings,
            ProductModerationSummary moderation, CategorySummary categories,
            ListingEnforcementSummary listingEnforcement, Instant generatedAt) { }

    public record OwnerTrendPoint(Instant bucketStart, Instant bucketEnd, long value) { }
    public record OwnerTrend(String metric, OwnerRange range, String granularity,
            List<OwnerTrendPoint> points, Long total, Instant generatedAt) { }

    public record OwnerResult<T>(T data, String safeMessage) {
        public boolean available() {
            return data != null;
        }

        public static <T> OwnerResult<T> available(T data) {
            return new OwnerResult<>(data, null);
        }

        public static <T> OwnerResult<T> unavailable(String message) {
            return new OwnerResult<>(null, message);
        }
    }
}
