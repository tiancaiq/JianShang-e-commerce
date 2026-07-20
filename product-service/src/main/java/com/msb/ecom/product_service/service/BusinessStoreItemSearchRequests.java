package com.msb.ecom.product_service.service;

import com.msb.ecom.common.core.validation.TextInputs;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchRequest;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.repository.BusinessStoreItemSearchCriteria;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

final class BusinessStoreItemSearchRequests {

    private static final int MAX_LIMIT = 60;

    private BusinessStoreItemSearchRequests() {
    }

    // Converts seller catalog query parameters into stable business-scoped repository criteria.
    static BusinessStoreItemSearchCriteria criteria(BusinessStoreItemSearchRequest request) {
        String keyword = normalizedOptionalText("Search keyword", request.q(), 120);
        String status = normalizedStatus(request.status());
        SearchCursor cursor = decodeCursor(request.cursor());
        return new BusinessStoreItemSearchCriteria(
                keyword,
                status,
                cursor == null ? null : cursor.updatedAt(),
                cursor == null ? null : cursor.listingId());
    }

    // Bounds seller catalog pages while retaining a practical dashboard default.
    static int normalizedLimit(Integer limit, int defaultLimit) {
        if (limit == null) {
            return defaultLimit;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be at least 1.");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    // Encodes the stable updated-at and ID position of the last visible management row.
    static String encodeCursor(ListingDraftResponse listing) {
        String raw = listing.updatedAt() + "\t" + listing.id();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static SearchCursor decodeCursor(String value) {
        String normalized = normalizedOptionalText("Cursor", value, 512);
        if (normalized == null) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(normalized), StandardCharsets.UTF_8);
            String[] parts = raw.split("\t", -1);
            if (parts.length != 2 || parts[1].length() != 26) {
                throw new IllegalArgumentException("Cursor is invalid.");
            }
            return new SearchCursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cursor is invalid.");
        }
    }

    private static String normalizedStatus(String value) {
        String normalized = normalizedOptionalText("Status", value, 30);
        if (normalized == null) {
            return null;
        }
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "DRAFT", "ACTIVE", "PAUSED", "REMOVED_BY_ADMIN" -> normalized.toUpperCase(Locale.ROOT);
            default -> throw new IllegalArgumentException(
                    "Status must be DRAFT, ACTIVE, PAUSED, or REMOVED_BY_ADMIN.");
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
            Instant updatedAt,
            String listingId) {
    }
}
