package com.msb.ecom.auth_service.analytics;

import java.time.Instant;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static com.msb.ecom.auth_service.analytics.OwnerAnalyticsContracts.*;

public interface AnalyticsOwnerClient {
    OwnerResult<OrderSummary> orderSummary(Instant from, Instant to, boolean compare,
            boolean includeFinancialAmounts, String correlationId);

    OwnerResult<ProductSummary> productSummary(Instant from, Instant to,
            String correlationId);

    OwnerResult<PaymentSummary> paymentSummary(Instant from, Instant to, boolean compare,
            boolean includeFinancialAmounts, String correlationId);

    OwnerResult<OwnerTrend> orderTrend(String metric, Instant from, Instant to,
            Granularity granularity, String correlationId);

    OwnerResult<OwnerTrend> productListingTrend(Instant from, Instant to,
            Granularity granularity, String correlationId);

    OwnerResult<OwnerTrend> paymentTrend(String metric, Instant from, Instant to,
            Granularity granularity, String correlationId);
}
