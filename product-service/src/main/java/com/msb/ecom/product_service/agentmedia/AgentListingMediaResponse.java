package com.msb.ecom.product_service.agentmedia;

import java.util.List;

public record AgentListingMediaResponse(
        String schemaVersion,
        String listingId,
        String listingVersion,
        String eligibility,
        List<Media> media
) {

    public record Media(
            String mediaId,
            String contentType,
            long byteSize,
            String sha256,
            String sourceVersion,
            String contentBase64
    ) {
    }
}
