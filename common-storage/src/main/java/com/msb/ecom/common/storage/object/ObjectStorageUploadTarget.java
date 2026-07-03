package com.msb.ecom.common.storage.object;

public record ObjectStorageUploadTarget(
        String bucket,
        String objectKey,
        String uploadMethod,
        String uploadUrl
) {
}
