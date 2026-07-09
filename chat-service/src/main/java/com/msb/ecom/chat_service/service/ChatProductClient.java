package com.msb.ecom.chat_service.service;

import com.msb.ecom.chat_service.dto.ProductListingEligibilityResponse;

public interface ChatProductClient {

    ProductListingEligibilityResponse listingEligibility(String bearerToken, String listingId);

    void completeListingTrade(
            String bearerToken,
            String listingId,
            String conversationId,
            String sellerUserId,
            String buyerUserId,
            int quantitySold);
}
