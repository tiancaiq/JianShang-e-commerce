package com.msb.ecom.payment_service.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class PaymentAnalyticsContracts {
    private PaymentAnalyticsContracts() { }

    public enum TrendMetric { PAYMENTS_SUCCEEDED, REFUNDS_SUCCEEDED }
    public enum Granularity { HOUR, DAY, WEEK }

    public record AnalyticsRange(Instant from, Instant to, String timezone,
                                 Instant previousFrom, Instant previousTo) { }

    public record MoneyAmount(String currency, BigDecimal amount) { }

    public record RefundAmountMetrics(String currency, BigDecimal requestedAmount,
                                      BigDecimal succeededAmount, BigDecimal failedAmount) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PaymentWindow(long paymentsSucceeded, long paymentsFailed,
                                BigDecimal paymentSuccessRatePercent,
                                List<MoneyAmount> succeededPaymentAmounts) {
        public PaymentWindow {
            succeededPaymentAmounts = succeededPaymentAmounts == null
                    ? null : List.copyOf(succeededPaymentAmounts);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundWindow(long refundsRequested, long refundsSucceeded, long refundsFailed,
                               long fullRefundsRequested, long partialRefundsRequested,
                               long fullRefundsSucceeded, long partialRefundsSucceeded,
                               long fullRefundsFailed, long partialRefundsFailed,
                               long returnRefundsSucceeded,
                               List<RefundAmountMetrics> amountMetrics) {
        public RefundWindow {
            amountMetrics = amountMetrics == null ? null : List.copyOf(amountMetrics);
        }
    }

    public record Window(PaymentWindow payments, RefundWindow refunds) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefundBacklog(long pendingCount, long processingCount,
                                Instant oldestActiveCreatedAt, Long oldestActiveAgeSeconds,
                                List<MoneyAmount> activeAmounts) {
        public RefundBacklog {
            activeAmounts = activeAmounts == null ? null : List.copyOf(activeAmounts);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(AnalyticsRange range, Window current, Window previous,
                          RefundBacklog currentRefundBacklog, Instant generatedAt) { }

    public record TrendPoint(Instant bucketStart, long value) { }

    public record Trend(TrendMetric metric, AnalyticsRange range, Granularity granularity,
                        List<TrendPoint> points, Instant generatedAt) {
        public Trend {
            points = List.copyOf(points);
        }
    }
}
