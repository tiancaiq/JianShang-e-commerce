package com.msb.ecom.auth_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class RestProductBusinessListingSummaryClient implements ProductBusinessListingSummaryClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestProductBusinessListingSummaryClient(
            RestClient.Builder builder,
            @Value("${service.product.url}") String productServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(productServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public Map<String, Summary> summaries(Set<String> businessIds) {
        if (businessIds == null || businessIds.isEmpty()) {
            return Map.of();
        }
        try {
            SummaryEnvelope response = client.post()
                    .uri("/api/v1/internal/admin/businesses/listing-summaries")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header(CorrelationId.HEADER_NAME, correlationId())
                    .body(Map.of("businessIds", businessIds))
                    .retrieve()
                    .body(SummaryEnvelope.class);
            if (response == null || response.items() == null) {
                throw new BusinessListingSummaryUnavailableException();
            }
            return response.items().stream().collect(Collectors.toUnmodifiableMap(
                    Summary::businessId, Function.identity(), (first, ignored) -> first));
        } catch (BusinessListingSummaryUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("Product business listing summary failed type={}", exception.getClass().getSimpleName());
            throw new BusinessListingSummaryUnavailableException();
        }
    }

    private String correlationId() {
        return CorrelationId.acceptOrGenerate(MDC.get(CorrelationIdFilter.MDC_KEY)).value();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SummaryEnvelope(List<Summary> items) {
    }
}
