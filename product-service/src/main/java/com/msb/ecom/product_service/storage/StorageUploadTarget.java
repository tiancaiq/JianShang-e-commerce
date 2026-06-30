package com.msb.ecom.product_service.storage;

public record StorageUploadTarget(
        String bucket,
        String objectKey,
        String uploadMethod,
        String uploadUrl
) {
}
