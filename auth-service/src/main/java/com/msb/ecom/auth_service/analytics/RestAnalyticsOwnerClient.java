package com.msb.ecom.auth_service.analytics;

import com.msb.ecom.common.web.correlation.CorrelationId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static com.msb.ecom.auth_service.analytics.OwnerAnalyticsContracts.*;

@Component
public class RestAnalyticsOwnerClient implements AnalyticsOwnerClient {
    private static final String TOKEN_HEADER = "X-Internal-Service-Token";
    private final RestClient order;
    private final RestClient product;
    private final RestClient payment;
    private final String token;

    public RestAnalyticsOwnerClient(RestClient.Builder builder,
            @Value("${service.order.url:http://localhost:8081}") String orderUrl,
            @Value("${service.product.url:http://localhost:8091}") String productUrl,
            @Value("${service.payment.url:http://localhost:8084}") String paymentUrl,
            @Value("${commerce.internal-service-token}") String token) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        requests.setReadTimeout(Duration.ofSeconds(2));
        this.order = builder.clone().requestFactory(requests).baseUrl(orderUrl).build();
        this.product = builder.clone().requestFactory(requests).baseUrl(productUrl).build();
        this.payment = builder.clone().requestFactory(requests).baseUrl(paymentUrl).build();
        this.token = token;
    }

    @Override
    public OwnerResult<OrderSummary> orderSummary(Instant from, Instant to, boolean compare,
            boolean includeFinancialAmounts, String correlationId) {
        try {
            OrderSummary summary = order.get().uri(uri -> uri
                            .path("/api/v1/internal/admin/analytics/summary")
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .queryParam("timezone", "UTC")
                            .queryParam("compare", compare)
                            .queryParam("includeFinancialAmounts", includeFinancialAmounts)
                            .build())
                    .header(TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve().body(OrderSummary.class);
            return summary == null ? OwnerResult.unavailable("Order analytics returned no data.")
                    : OwnerResult.available(summary);
        } catch (RestClientException exception) {
            return OwnerResult.unavailable("Order analytics is temporarily unavailable.");
        }
    }

    @Override
    public OwnerResult<ProductSummary> productSummary(Instant from, Instant to,
            String correlationId) {
        try {
            ProductSummary summary = product.get().uri(uri -> uri
                            .path("/api/v1/internal/admin/analytics/summary")
                            .queryParam("from", from).queryParam("to", to).build())
                    .header(TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve().body(ProductSummary.class);
            return summary == null ? OwnerResult.unavailable("Catalog analytics returned no data.")
                    : OwnerResult.available(summary);
        } catch (RestClientException exception) {
            return OwnerResult.unavailable("Catalog analytics is temporarily unavailable.");
        }
    }

    @Override
    public OwnerResult<PaymentSummary> paymentSummary(Instant from, Instant to,
            boolean compare, boolean includeFinancialAmounts, String correlationId) {
        try {
            PaymentSummary summary = payment.get().uri(uri -> uri
                            .path("/api/v1/internal/admin/analytics/summary")
                            .queryParam("from", from).queryParam("to", to)
                            .queryParam("timezone", "UTC").queryParam("compare", compare)
                            .queryParam("includeFinancialAmounts", includeFinancialAmounts).build())
                    .header(TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve().body(PaymentSummary.class);
            return summary == null ? OwnerResult.unavailable("Payment analytics returned no data.")
                    : OwnerResult.available(summary);
        } catch (RestClientException exception) {
            return OwnerResult.unavailable("Payment analytics is temporarily unavailable.");
        }
    }

    @Override
    public OwnerResult<OwnerTrend> orderTrend(String metric, Instant from, Instant to,
            Granularity granularity, String correlationId) {
        try {
            OwnerTrend trend = order.get().uri(uri -> uri
                            .path("/api/v1/internal/admin/analytics/trends")
                            .queryParam("metric", metric).queryParam("from", from)
                            .queryParam("to", to).queryParam("timezone", "UTC")
                            .queryParam("granularity", granularity).build())
                    .header(TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve().body(OwnerTrend.class);
            return trend == null ? OwnerResult.unavailable("Order trend returned no data.")
                    : OwnerResult.available(trend);
        } catch (RestClientException exception) {
            return OwnerResult.unavailable("Order trend is temporarily unavailable.");
        }
    }

    @Override
    public OwnerResult<OwnerTrend> productListingTrend(Instant from, Instant to,
            Granularity granularity, String correlationId) {
        try {
            OwnerTrend trend = product.get().uri(uri -> uri
                            .path("/api/v1/internal/admin/analytics/trends/listings-created")
                            .queryParam("from", from).queryParam("to", to)
                            .queryParam("granularity", granularity).build())
                    .header(TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve().body(OwnerTrend.class);
            return trend == null ? OwnerResult.unavailable("Listing trend returned no data.")
                    : OwnerResult.available(trend);
        } catch (RestClientException exception) {
            return OwnerResult.unavailable("Listing trend is temporarily unavailable.");
        }
    }

    @Override
    public OwnerResult<OwnerTrend> paymentTrend(String metric, Instant from, Instant to,
            Granularity granularity, String correlationId) {
        try {
            OwnerTrend trend = payment.get().uri(uri -> uri
                            .path("/api/v1/internal/admin/analytics/trends")
                            .queryParam("metric", metric).queryParam("from", from)
                            .queryParam("to", to).queryParam("timezone", "UTC")
                            .queryParam("granularity", granularity).build())
                    .header(TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .retrieve().body(OwnerTrend.class);
            return trend == null ? OwnerResult.unavailable("Payment trend returned no data.")
                    : OwnerResult.available(trend);
        } catch (RestClientException exception) {
            return OwnerResult.unavailable("Payment trend is temporarily unavailable.");
        }
    }
}
