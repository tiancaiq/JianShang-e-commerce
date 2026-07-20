package com.msb.ecom.product_service.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ListingKnowledgeSourceResponse(
        String sourceType,
        String sourceId,
        String sourceVersion,
        String supersedesVersion,
        String lifecycle,
        String visibility,
        String language,
        Instant effectiveFrom,
        Instant invalidatedAt,
        Instant sourcePublishedAt,
        String contentHash,
        Content content
) {

    public static ListingKnowledgeSourceResponse from(ListingKnowledgeVersion version) {
        Content content = "ACTIVE".equals(version.lifecycle())
                ? new Content(
                        version.title(),
                        version.description(),
                        new Price(version.priceAmount().toPlainString(), version.currency()),
                        new PublicLocation(version.publicCity(), version.publicRegion()))
                : null;
        return new ListingKnowledgeSourceResponse(
                "LISTING",
                version.listingId(),
                Long.toString(version.sourceVersion()),
                version.supersedesVersion() == null ? null : Long.toString(version.supersedesVersion()),
                version.lifecycle(),
                version.visibility(),
                version.language(),
                version.effectiveFrom(),
                version.invalidatedAt(),
                version.sourcePublishedAt(),
                version.contentHash(),
                content);
    }

    public record Content(
            String title,
            String description,
            Price price,
            PublicLocation publicLocation
    ) {
    }

    public record Price(String amount, String currency) {
    }

    public record PublicLocation(String city, String region) {
    }
}
