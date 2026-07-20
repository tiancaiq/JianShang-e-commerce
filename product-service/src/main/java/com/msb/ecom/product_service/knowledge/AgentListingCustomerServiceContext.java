package com.msb.ecom.product_service.knowledge;

public record AgentListingCustomerServiceContext(
        String listingId,
        String sourceVersion,
        boolean eligible,
        String sellerType,
        String title,
        String thumbnailUrl,
        String transactionNotice
) {
}
