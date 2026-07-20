package com.msb.ecom.product_service.knowledge;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class ListingKnowledgeSourceService {

    private static final int DEFAULT_EXPORT_LIMIT = 100;
    private static final int MAX_EXPORT_LIMIT = 200;
    private static final String CURSOR_VERSION = "v1";

    private final ListingKnowledgeRepository repository;
    private final String internalServiceToken;

    public ListingKnowledgeSourceService(
            ListingKnowledgeRepository repository,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalStateException("Agent internal service token must be configured.");
        }
        this.repository = repository;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Returns exactly one immutable listing source version to the authenticated agent service.
    public ListingKnowledgeSourceResponse exact(
            String suppliedToken,
            String listingId,
            long sourceVersion) {
        requireInternalToken(suppliedToken);
        String normalizedListingId = FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
        if (sourceVersion < 0) {
            throw new IllegalArgumentException("Source version must not be negative.");
        }
        return repository.findExact(normalizedListingId, sourceVersion)
                .map(ListingKnowledgeSourceResponse::from)
                .orElseThrow(ListingKnowledgeSourceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    // Pages a watermark-stable view of current active listing sources for rebuilds.
    public ListingKnowledgeExportResponse export(
            String suppliedToken,
            String cursor,
            Integer requestedLimit) {
        requireInternalToken(suppliedToken);
        int limit = normalizedLimit(requestedLimit);
        ExportCursor decoded = decodeCursor(cursor);
        Instant watermark = decoded == null ? Instant.now() : decoded.watermark();
        String afterListingId = decoded == null ? null : decoded.afterListingId();

        List<ListingKnowledgeVersion> fetched =
                repository.findActiveAtWatermark(watermark, afterListingId, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<ListingKnowledgeVersion> page = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(new ExportCursor(watermark, page.get(page.size() - 1).listingId()))
                : null;
        return new ListingKnowledgeExportResponse(
                page.stream().map(ListingKnowledgeSourceResponse::from).toList(),
                nextCursor,
                hasMore,
                watermark);
    }

    private int normalizedLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_EXPORT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_EXPORT_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and 200.");
        }
        return limit;
    }

    private void requireInternalToken(String suppliedToken) {
        byte[] expected = internalServiceToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ListingAuthorizationException("Agent source authentication is required.");
        }
    }

    private String encodeCursor(ExportCursor cursor) {
        String raw = CURSOR_VERSION + "|" + cursor.watermark() + "|" + cursor.afterListingId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ExportCursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3 || !CURSOR_VERSION.equals(parts[0])) {
                throw new IllegalArgumentException("Invalid listing knowledge export cursor.");
            }
            Instant watermark = Instant.parse(parts[1]);
            if (watermark.isAfter(Instant.now())) {
                throw new IllegalArgumentException("Invalid listing knowledge export cursor.");
            }
            return new ExportCursor(
                    watermark,
                    FixedLengthIds.requireTrimmed("Cursor listing ID", parts[2], 26));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid listing knowledge export cursor.");
        }
    }

    private record ExportCursor(Instant watermark, String afterListingId) {
    }
}
