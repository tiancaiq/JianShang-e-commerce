package com.msb.ecom.product_service.search.hybrid;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingHybridSearchListing(
        String listingId,
        long listingVersion,
        String categoryId,
        String categorySlug,
        String categoryName,
        String title,
        String condition,
        BigDecimal priceAmount,
        String currency,
        String publicCity,
        String publicRegion,
        boolean available,
        String primaryImageUrl,
        Instant publishedAt,
        String description,
        String sellerType,
        String businessId
) {
    public ListingHybridSearchListing(
            String listingId,
            long listingVersion,
            String categoryId,
            String categorySlug,
            String categoryName,
            String title,
            String condition,
            BigDecimal priceAmount,
            String currency,
            String publicCity,
            String publicRegion,
            boolean available,
            String primaryImageUrl,
            Instant publishedAt,
            String description) {
        this(listingId, listingVersion, categoryId, categorySlug, categoryName, title,
                condition, priceAmount, currency, publicCity, publicRegion, available,
                primaryImageUrl, publishedAt, description, "INDIVIDUAL", null);
    }

    public ListingHybridSearchListing(
            String listingId,
            long listingVersion,
            String categoryId,
            String categorySlug,
            String categoryName,
            String title,
            String condition,
            BigDecimal priceAmount,
            String currency,
            String publicCity,
            String publicRegion,
            boolean available,
            String primaryImageUrl,
            Instant publishedAt) {
        this(listingId, listingVersion, categoryId, categorySlug, categoryName, title,
                condition, priceAmount, currency, publicCity, publicRegion, available,
                primaryImageUrl, publishedAt, "", "INDIVIDUAL", null);
    }
}
