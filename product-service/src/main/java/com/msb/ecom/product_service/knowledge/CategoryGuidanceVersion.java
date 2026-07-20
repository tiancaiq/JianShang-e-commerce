package com.msb.ecom.product_service.knowledge;

import java.time.Instant;

public record CategoryGuidanceVersion(
        String categoryId,
        String language,
        long sourceVersion,
        Long supersedesVersion,
        String lifecycle,
        String visibility,
        String categorySlug,
        String categoryName,
        String title,
        String body,
        String contentHash,
        Instant effectiveFrom,
        Instant invalidatedAt,
        String actorUserId,
        String correlationId,
        Instant createdAt
) {
}
