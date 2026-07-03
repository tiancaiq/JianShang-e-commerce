package com.msb.ecom.auth_service.service;

public record AvatarUploadTarget(
        String bucket,
        String objectKey,
        String uploadMethod,
        String uploadUrl
) {
}
