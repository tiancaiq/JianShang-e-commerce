package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryGuidanceSourceResponse(
        String sourceType,
        String sourceId,
        String sourceVersion,
        String supersedesVersion,
        String lifecycle,
        String visibility,
        String language,
        Instant effectiveFrom,
        Instant invalidatedAt,
        String contentHash,
        Content content
) {
    public static CategoryGuidanceSourceResponse from(CategoryGuidanceVersion version) {
        Content content = "ACTIVE".equals(version.lifecycle())
                ? new Content(
                        version.categorySlug(),
                        version.categoryName(),
                        version.title(),
                        version.body())
                : null;
        return new CategoryGuidanceSourceResponse(
                "CATEGORY_GUIDANCE",
                version.categoryId(),
                Long.toString(version.sourceVersion()),
                version.supersedesVersion() == null ? null : Long.toString(version.supersedesVersion()),
                version.lifecycle(),
                version.visibility(),
                version.language(),
                version.effectiveFrom(),
                version.invalidatedAt(),
                version.contentHash(),
                content);
    }

    public record Content(String categorySlug, String categoryName, String title, String body) {
    }
}
