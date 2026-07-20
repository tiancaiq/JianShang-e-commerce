package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.CheckoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class RestBuyerIdentityClient implements BuyerIdentityClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final RestClient client;
    private final String internalServiceToken;

    public RestBuyerIdentityClient(
            RestClient.Builder builder,
            @Value("${service.auth.url}") String authServiceUrl,
            @Value("${commerce.internal-service-token}") String internalServiceToken) {
        this.client = builder.baseUrl(authServiceUrl).build();
        this.internalServiceToken = internalServiceToken;
    }

    @Override
    public BuyerAddress resolveAddress(String subject, String addressId) {
        try {
            BuyerAddress response = client.post()
                    .uri("/api/v1/internal/users/checkout-address-resolution")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(Map.of("subject", subject, "addressId", addressId))
                    .retrieve()
                    .body(BuyerAddress.class);
            if (response == null) {
                throw unavailable();
            }
            return response;
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_ADDRESS_NOT_FOUND",
                    "The selected address is unavailable.");
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    @Override
    public String resolveBuyer(String subject) {
        try {
            BuyerResolution response = client.post()
                    .uri("/api/v1/internal/users/checkout-buyer-resolution")
                    .header(INTERNAL_TOKEN_HEADER, internalServiceToken)
                    .body(Map.of("subject", subject))
                    .retrieve()
                    .body(BuyerResolution.class);
            if (response == null || response.buyerId() == null) {
                throw unavailable();
            }
            return response.buyerId();
        } catch (HttpClientErrorException.NotFound exception) {
            throw new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_NOT_FOUND",
                    "Checkout was not found.");
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw unavailable();
        }
    }

    private CheckoutException unavailable() {
        return new CheckoutException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CHECKOUT_DEPENDENCY_UNAVAILABLE",
                "Buyer address validation is temporarily unavailable.");
    }

    private record BuyerResolution(String buyerId) {
    }
}
