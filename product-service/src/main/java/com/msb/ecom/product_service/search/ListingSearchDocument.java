package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.PublicListingResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;

public record ListingSearchDocument(
        String listingId,
        String sellerType,
        String categoryId,
        String categorySlug,
        String categoryName,
        String title,
        String description,
        String condition,
        BigDecimal priceAmount,
        String currency,
        String publicCity,
        String publicCityKey,
        String publicRegion,
        String publicRegionKey,
        Instant publishedAt
) {

    public static ListingSearchDocument from(PublicListingResponse listing) {
        return new ListingSearchDocument(
                listing.id(),
                listing.sellerType(),
                listing.categoryId(),
                listing.categorySlug(),
                listing.categoryName(),
                listing.title(),
                listing.description(),
                listing.condition(),
                listing.priceAmount(),
                listing.currency(),
                listing.publicCity(),
                key(listing.publicCity()),
                listing.publicRegion(),
                key(listing.publicRegion()),
                listing.publishedAt());
    }

    private static String key(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
