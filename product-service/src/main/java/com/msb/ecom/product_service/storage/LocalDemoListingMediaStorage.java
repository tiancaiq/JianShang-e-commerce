package com.msb.ecom.product_service.storage;

import com.msb.ecom.common.storage.object.LocalObjectStorageTargets;
import com.msb.ecom.common.storage.object.ObjectStorageUploadTarget;

public class LocalDemoListingMediaStorage implements ListingMediaStorage {

    private static final String BUCKET = "listing-media-local";

    @Override
    public String bucket() {
        return BUCKET;
    }

    @Override
    public StorageUploadTarget createUploadTarget(String objectKey, String contentType, long sizeBytes) {
        ObjectStorageUploadTarget target = LocalObjectStorageTargets.localDemoTarget(BUCKET, objectKey);
        return new StorageUploadTarget(target.bucket(), target.objectKey(), target.uploadMethod(), target.uploadUrl());
    }

    @Override
    public void uploadObject(String objectKey, String contentType, byte[] bytes) {
        // Local demo mode keeps media metadata-only so offline development has no storage dependency.
    }

    @Override
    public void verifyUploaded(String objectKey, String expectedContentType, long expectedSizeBytes) {
        // Local demo mode preserves older metadata-only behavior for offline development.
    }

    @Override
    public byte[] readObject(String objectKey) {
        return new byte[0];
    }

    @Override
    public void verifyReadable(String objectKey) {
        // Local demo mode has no object bytes to verify.
    }

}
