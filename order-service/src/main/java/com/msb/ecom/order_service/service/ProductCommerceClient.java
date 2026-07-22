package com.msb.ecom.order_service.service;

import java.math.BigDecimal;
import java.util.Optional;

public interface ProductCommerceClient {

    Optional<ProductContext> find(String listingId);

    record ProductContext(
            String listingId,
            String businessId,
            String storeId,
            String sellerType,
            String title,
            String sku,
            String condition,
            long version,
            BigDecimal priceAmount,
            String currency,
            String status,
            String thumbnailUrl,
            String storeName,
            String storeSlug,
            Boolean businessVerified,
            String publicCity,
            String publicRegion
    ) {
        public ProductContext(
                String listingId,
                String businessId,
                String storeId,
                String sellerType,
                String title,
                String sku,
                String condition,
                long version,
                BigDecimal priceAmount,
                String currency,
                String status,
                String thumbnailUrl) {
            this(
                    listingId,
                    businessId,
                    storeId,
                    sellerType,
                    title,
                    sku,
                    condition,
                    version,
                    priceAmount,
                    currency,
                    status,
                    thumbnailUrl,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public ProductContext(
                String listingId,
                String businessId,
                String storeId,
                String sellerType,
                String title,
                BigDecimal priceAmount,
                String currency,
                String status,
                String thumbnailUrl) {
            this(
                    listingId,
                    businessId,
                    storeId,
                    sellerType,
                    title,
                    null,
                    "UNSPECIFIED",
                    0,
                    priceAmount,
                    currency,
                    status,
                    thumbnailUrl,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }
}
