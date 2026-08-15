package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.PublicListingImageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record ListingSearchDocument(
        String listingId,
        String sellerType,
        String categoryId,
        String categorySlug,
        String categoryName,
        String title,
        String description,
        String searchText,
        String condition,
        BigDecimal priceAmount,
        String currency,
        String publicCity,
        String publicCityKey,
        String publicRegion,
        String publicRegionKey,
        Instant publishedAt,
        boolean inventoryAvailable,
        String primaryImageUrl
) {

    public static ListingSearchDocument from(PublicListingResponse listing) {
        return from(listing, listing.images());
    }

    // Builds the allowlisted projection from the authoritative public row and public media only.
    public static ListingSearchDocument from(
            PublicListingResponse listing,
            List<PublicListingImageResponse> images) {
        return new ListingSearchDocument(
                listing.id(),
                listing.sellerType(),
                listing.categoryId(),
                listing.categorySlug(),
                listing.categoryName(),
                listing.title(),
                listing.description(),
                searchText(listing),
                listing.condition(),
                listing.priceAmount(),
                listing.currency(),
                listing.publicCity(),
                key(listing.publicCity()),
                listing.publicRegion(),
                key(listing.publicRegion()),
                listing.publishedAt(),
                listing.quantity() > 0,
                primaryImageUrl(images));
    }

    // Keeps one bounded denormalized lexical field without adding private listing data.
    private static String searchText(PublicListingResponse listing) {
        return String.join(" ", List.of(
                safe(listing.title()),
                safe(listing.description()),
                safe(listing.categoryName()),
                safe(listing.categorySlug()),
                safe(listing.condition()),
                safe(listing.publicCity()),
                safe(listing.publicRegion()))).trim();
    }

    private static String primaryImageUrl(List<PublicListingImageResponse> images) {
        return images == null || images.isEmpty() ? null : images.get(0).url();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String key(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
