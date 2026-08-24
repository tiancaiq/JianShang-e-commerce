package com.msb.ecom.order_service.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderAnalyticsContracts {
    private OrderAnalyticsContracts() { }

    public enum TrendMetric { ORDERS_CREATED, DISPUTES_OPENED }
    public enum Granularity { HOUR, DAY, WEEK }

    public record AnalyticsRange(Instant from, Instant to, String timezone,
                                 Instant previousFrom, Instant previousTo) { }

    public record MoneyAmount(String currency, BigDecimal amount) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderWindow(long ordersCreated, long ordersConfirmed,
                              long currentCancelledOrders, long fullyDeliveredOrders,
                              List<MoneyAmount> grossCreatedAmounts) {
        public OrderWindow {
            grossCreatedAmounts = grossCreatedAmounts == null ? null : List.copyOf(grossCreatedAmounts);
        }
    }

    public record DisputeWindow(long disputesOpened, long disputesResolved,
                                long resolvedNoAction, long returnsApproved,
                                long refundsRecommended, long partialRefundsRecommended) { }

    public record Window(OrderWindow orders, DisputeWindow disputes) { }

    public record DisputeBacklog(long openCount, long unassignedCount,
                                 Instant oldestOpenCreatedAt, Long oldestOpenAgeSeconds) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(AnalyticsRange range, Window current, Window previous,
                          DisputeBacklog currentDisputeBacklog, Instant generatedAt) { }

    public record TrendPoint(Instant bucketStart, long value) { }

    public record Trend(TrendMetric metric, AnalyticsRange range, Granularity granularity,
                        List<TrendPoint> points, Instant generatedAt) {
        public Trend {
            points = List.copyOf(points);
        }
    }
}
