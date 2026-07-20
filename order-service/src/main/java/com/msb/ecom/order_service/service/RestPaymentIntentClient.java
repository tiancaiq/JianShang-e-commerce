package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.order_service.config.CheckoutPaymentProperties;
import com.msb.ecom.order_service.model.CheckoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class RestPaymentIntentClient implements PaymentIntentClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;
    private final ObjectMapper objectMapper;

    @Autowired
    public RestPaymentIntentClient(
            RestClient.Builder builder,
            CheckoutPaymentProperties properties,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = builder
                .baseUrl(properties.serviceUrl())
                .requestFactory(requestFactory)
                .build();
        this.internalServiceToken = properties.internalServiceToken();
        this.objectMapper = objectMapper;
    }

    RestPaymentIntentClient(
            RestClient client,
            String internalServiceToken,
            ObjectMapper objectMapper) {
        this.client = client;
        this.internalServiceToken = internalServiceToken;
        this.objectMapper = objectMapper;
    }

    // Calls the service-authenticated payment boundary exactly once without automatic retries.
    @Override
    public PaymentIntent create(String idempotencyKey, String correlationId, Command command) {
        try {
            PaymentIntent response = client.post()
                    .uri("/api/v1/internal/payment-intents")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .header("Idempotency-Key", idempotencyKey)
                    .header(CorrelationId.HEADER_NAME, correlationId)
                    .body(command)
                    .retrieve()
                    .body(PaymentIntent.class);
            if (response == null) {
                throw unavailable();
            }
            return response;
        } catch (HttpStatusCodeException exception) {
            throw mapped(exception);
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private CheckoutException mapped(HttpStatusCodeException exception) {
        String code = errorCode(exception);
        if (exception.getStatusCode().value() == 409) {
            String stableCode = switch (code) {
                case "PAYMENT_IDEMPOTENCY_CONFLICT",
                        "PAYMENT_INTENT_ALREADY_EXISTS",
                        "PAYMENT_IDEMPOTENCY_INCOMPLETE" -> code;
                default -> "CHECKOUT_PAYMENT_CONFLICT";
            };
            return new CheckoutException(
                    HttpStatus.CONFLICT,
                    stableCode,
                    "Payment intent could not be created for this checkout.");
        }
        if (exception.getStatusCode().value() == 400
                && "PAYMENT_IDEMPOTENCY_KEY_REQUIRED".equals(code)) {
            return new CheckoutException(
                    HttpStatus.BAD_REQUEST,
                    "PAYMENT_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
        if (exception.getStatusCode().value() == 404
                && "PAYMENT_INTENTS_DISABLED".equals(code)) {
            return new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_PAYMENT_NOT_AVAILABLE",
                    "Payment is not available.");
        }
        return unavailable();
    }

    private String errorCode(HttpStatusCodeException exception) {
        try {
            ErrorEnvelope envelope =
                    objectMapper.readValue(exception.getResponseBodyAsByteArray(), ErrorEnvelope.class);
            return envelope.error() == null ? null : envelope.error().code();
        } catch (Exception ignored) {
            return null;
        }
    }

    private CheckoutException unavailable() {
        return new CheckoutException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CHECKOUT_PAYMENT_UNAVAILABLE",
                "Payment is temporarily unavailable.");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorEnvelope(ErrorBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(String code) {
    }
}
