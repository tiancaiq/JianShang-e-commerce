package com.msb.ecom.chat_service.service;

import com.msb.ecom.chat_service.dto.ProductListingEligibilityResponse;
import com.msb.ecom.chat_service.dto.CompleteListingTradeRequest;
import com.msb.ecom.chat_service.model.ChatDependencyUnavailableException;
import com.msb.ecom.chat_service.model.ChatListingNotEligibleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class RestChatProductClient implements ChatProductClient {

    private final RestClient restClient;

    public RestChatProductClient(
            RestClient.Builder restClientBuilder,
            @Value("${service.product.url}") String productServiceUrl) {
        this.restClient = restClientBuilder.baseUrl(productServiceUrl).build();
    }

    @Override
    public ProductListingEligibilityResponse listingEligibility(String bearerToken, String listingId) {
        ProductListingEligibilityResponse response = restClient.get()
                .uri("/api/v1/internal/chat/listings/{listingId}/conversation-eligibility", listingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .retrieve()
                .onStatus(status -> status.value() == 404 || status.value() == 400,
                        (request, clientResponse) -> {
                            log.warn("Product-service rejected chat listing eligibility listingId={} status={}",
                                    listingId, clientResponse.getStatusCode().value());
                            throw new ChatListingNotEligibleException("Listing is not available for chat.");
                        })
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Product-service chat listing eligibility failed listingId={} status={}",
                            listingId, clientResponse.getStatusCode().value());
                    throw new ChatDependencyUnavailableException("Listing validation is temporarily unavailable.");
                })
                .body(ProductListingEligibilityResponse.class);

        if (response == null || !response.eligible()) {
            throw new ChatListingNotEligibleException("Listing is not available for chat.");
        }
        return response;
    }

    @Override
    public void completeListingTrade(
            String bearerToken,
            String listingId,
            String conversationId,
            String sellerUserId,
            String buyerUserId,
            int quantitySold) {
        restClient.post()
                .uri("/api/v1/internal/chat/listings/{listingId}/complete-trade", listingId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .body(new CompleteListingTradeRequest(conversationId, sellerUserId, buyerUserId, quantitySold))
                .retrieve()
                .onStatus(status -> status.value() == 404 || status.value() == 400 || status.value() == 409,
                        (request, clientResponse) -> {
                            log.warn("Product-service rejected trade completion listingId={} conversationId={} status={}",
                                    listingId, conversationId, clientResponse.getStatusCode().value());
                            throw new ChatListingNotEligibleException("Listing is not available for completion.");
                        })
                .onStatus(HttpStatusCode::isError, (request, clientResponse) -> {
                    log.warn("Product-service trade completion failed listingId={} conversationId={} status={}",
                            listingId, conversationId, clientResponse.getStatusCode().value());
                    throw new ChatDependencyUnavailableException("Listing completion is temporarily unavailable.");
                })
                .toBodilessEntity();
    }
}
