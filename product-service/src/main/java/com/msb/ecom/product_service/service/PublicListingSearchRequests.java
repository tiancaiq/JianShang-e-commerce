package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import com.msb.ecom.product_service.dto.PublicListingSearchRequest;
import com.msb.ecom.product_service.model.ListingCondition;
import com.msb.ecom.product_service.repository.PublicListingSearchCriteria;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

final class PublicListingSearchRequests {

    private static final int MAX_PUBLIC_SEARCH_LIMIT = 60;

    private PublicListingSearchRequests() {
    }

    // Converts browser search parameters into the repository criteria used by both MySQL and OpenSearch paths.
    static PublicListingSearchCriteria criteria(PublicListingSearchRequest request) {
        String keyword = normalizedOptionalText("Search keyword", request.q(), 120);
        String categoryId = normalizedOptionalText("Category ID", request.categoryId(), 26);
        String condition = normalizedOptionalCondition(request.condition());
        BigDecimal minPrice = normalizedOptionalPrice(request.minPrice(), "Minimum price");
        BigDecimal maxPrice = normalizedOptionalPrice(request.maxPrice(), "Maximum price");
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new IllegalArgumentException("Minimum price must be less than or equal to maximum price.");
        }
        String city = normalizedOptionalText("City", request.city(), 100);
        String county = normalizedOptionalText("County", request.county(), 100);
        String sort = normalizedMarketplaceSort(request.sort());
        SearchCursor cursor = decodeCursor(request.cursor(), sort);
        return new PublicListingSearchCriteria(
                keyword,
                categoryId,
                condition,
                minPrice,
                maxPrice,
                city,
                county,
                sort,
                cursor == null ? null : cursor.priceAmount(),
                cursor == null ? null : cursor.publishedAt(),
                cursor == null ? null : cursor.listingId());
    }

    // Keeps public search pages bounded while preserving the browse default when callers omit a limit.
    static int normalizedLimit(Integer limit, int defaultLimit) {
        if (limit == null) {
            return defaultLimit;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be at least 1.");
        }
        return Math.min(limit, MAX_PUBLIC_SEARCH_LIMIT);
    }

    // Encodes only stable sort values from the last visible listing so cursor pagination remains deterministic.
    static String encodeCursor(String sort, PublicListingResponse listing) {
        String raw = String.join("\t",
                sort,
                listing.priceAmount().toPlainString(),
                listing.publishedAt().toString(),
                listing.id());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static SearchCursor decodeCursor(String value, String expectedSort) {
        String normalized = normalizedOptionalText("Cursor", value, 512);
        if (normalized == null) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(normalized), StandardCharsets.UTF_8);
            String[] parts = raw.split("\t", -1);
            if (parts.length != 4 || !expectedSort.equals(parts[0])) {
                throw new IllegalArgumentException("Cursor does not match the current sort.");
            }
            return new SearchCursor(parts[0], new BigDecimal(parts[1]), Instant.parse(parts[2]), parts[3]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cursor is invalid.");
        }
    }

    private static String normalizedOptionalCondition(String value) {
        String normalized = normalizedOptionalText("Condition", value, 30);
        if (normalized == null) {
            return null;
        }
        try {
            return ListingCondition.valueOf(normalized.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Condition must be NEW, OPEN_BOX, LIKE_NEW, GOOD, FAIR, or FOR_PARTS.");
        }
    }

    private static BigDecimal normalizedOptionalPrice(BigDecimal value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must be zero or greater.");
        }
        return value;
    }

    private static String normalizedMarketplaceSort(String value) {
        String normalized = normalizedOptionalText("Sort", value, 30);
        if (normalized == null) {
            return "newest";
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "newest", "price_asc", "price_desc" -> normalized.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("Sort must be newest, price_asc, or price_desc.");
        };
    }

    private static String normalizedOptionalText(String fieldName, String value, int maxLength) {
        String normalized = TextInputs.collapseWhitespaceToNull(value);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long.");
        }
        return normalized;
    }

    private record SearchCursor(
            String sort,
            BigDecimal priceAmount,
            Instant publishedAt,
            String listingId) {
    }
}
