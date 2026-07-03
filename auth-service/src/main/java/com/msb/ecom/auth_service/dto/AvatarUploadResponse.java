package com.msb.ecom.auth_service.dto;

public record AvatarUploadResponse(
        String objectBucket,
        String objectKey,
        String contentType,
        long sizeBytes,
        String uploadMethod,
        String uploadUrl
) {
}
