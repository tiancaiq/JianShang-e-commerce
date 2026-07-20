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
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class CategoryGuidanceSourceService {

    private static final Pattern LANGUAGE =
            Pattern.compile("[a-z]{2,3}(?:-[a-z0-9]{2,8})*");
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final CategoryGuidanceRepository repository;
    private final String internalServiceToken;

    public CategoryGuidanceSourceService(
            CategoryGuidanceRepository repository,
            @Value("${agent.internal-service-token}") String internalServiceToken) {
        if (internalServiceToken == null || internalServiceToken.isBlank()) {
            throw new IllegalStateException("Agent internal service token must be configured.");
        }
        this.repository = repository;
        this.internalServiceToken = internalServiceToken;
    }

    @Transactional(readOnly = true)
    // Returns only the exact immutable source version requested by Agent Service.
    public CategoryGuidanceSourceResponse exact(
            String suppliedToken,
            String categoryId,
            String language,
            long sourceVersion) {
        requireInternalToken(suppliedToken);
        String normalizedCategoryId =
                FixedLengthIds.requireTrimmed("Category ID", categoryId, 26);
        String normalizedLanguage = language(language);
        if (sourceVersion <= 0) {
            throw new IllegalArgumentException("Source version must be positive.");
        }
        return repository.findExact(normalizedCategoryId, normalizedLanguage, sourceVersion)
                .map(CategoryGuidanceSourceResponse::from)
                .orElseThrow(CategoryGuidanceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    // Pages a watermark-stable view of current active category-guidance sources.
    public CategoryGuidancePageResponse export(
            String suppliedToken,
            String cursor,
            Integer requestedLimit) {
        requireInternalToken(suppliedToken);
        int limit = limit(requestedLimit);
        ExportCursor decoded = decodeCursor(cursor);
        Instant watermark = decoded == null ? Instant.now() : decoded.watermark();
        String afterCategoryId = decoded == null ? null : decoded.afterCategoryId();
        String afterLanguage = decoded == null ? null : decoded.afterLanguage();
        List<CategoryGuidanceVersion> fetched = repository.findActiveAtWatermark(
                watermark,
                afterCategoryId,
                afterLanguage,
                limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<CategoryGuidanceVersion> page = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore && !page.isEmpty()
                ? encodeCursor(new ExportCursor(
                        watermark,
                        page.get(page.size() - 1).categoryId(),
                        page.get(page.size() - 1).language()))
                : null;
        return new CategoryGuidancePageResponse(
                page.stream().map(CategoryGuidanceSourceResponse::from).toList(),
                nextCursor,
                hasMore,
                watermark);
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

    private int limit(Integer requested) {
        int normalized = requested == null ? DEFAULT_LIMIT : requested;
        if (normalized < 1 || normalized > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and 200.");
        }
        return normalized;
    }

    private String language(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!LANGUAGE.matcher(normalized).matches() || normalized.length() > 35) {
            throw new IllegalArgumentException("Language must be a valid normalized language tag.");
        }
        return normalized;
    }

    private String encodeCursor(ExportCursor cursor) {
        String raw = "v1|" + cursor.watermark() + "|"
                + cursor.afterCategoryId() + "|" + cursor.afterLanguage();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ExportCursor decodeCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            Instant watermark = Instant.parse(parts[1]);
            if (watermark.isAfter(Instant.now())) {
                throw new IllegalArgumentException();
            }
            return new ExportCursor(
                    watermark,
                    FixedLengthIds.requireTrimmed("Cursor category ID", parts[2], 26),
                    language(parts[3]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid category guidance export cursor.");
        }
    }

    private record ExportCursor(Instant watermark, String afterCategoryId, String afterLanguage) {
    }
}
