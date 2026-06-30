package com.msb.ecom.product_service.dto;

public record PublicListingImageResponse(
        String id,
        int displayOrder,
        String altText,
        String originalFileName,
        String contentType,
        long sizeBytes,
        String uploadUrl,
        String url
) {
}
