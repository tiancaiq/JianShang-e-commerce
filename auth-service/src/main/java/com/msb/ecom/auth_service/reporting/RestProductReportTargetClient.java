package com.msb.ecom.auth_service.reporting;

import com.msb.ecom.auth_service.reporting.ReportContracts.ListingTargetContext;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

@Component
public class RestProductReportTargetClient implements ProductReportTargetClient {
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";
    private final RestClient client;
    private final String token;

    public RestProductReportTargetClient(RestClient.Builder builder,
            @Value("${service.product.url}") String productServiceUrl,
            @Value("${commerce.internal-service-token}") String token) {
        this.client = builder.baseUrl(productServiceUrl).build();
        this.token = token;
    }

    @Override
    public Optional<ListingTargetContext> listing(String listingId) {
        try {
            return Optional.ofNullable(client.get().uri("/api/v1/internal/reports/listings/{id}", listingId)
                    .header(INTERNAL_TOKEN_HEADER, token)
                    .header(CorrelationId.HEADER_NAME, CorrelationId.acceptOrGenerate(MDC.get(CorrelationIdFilter.MDC_KEY)).value())
                    .retrieve().body(ListingTargetContext.class));
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            throw ReportException.unavailable();
        } catch (RestClientException exception) {
            throw ReportException.unavailable();
        }
    }
}
