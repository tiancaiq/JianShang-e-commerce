package com.msb.ecom.product_service.storage;

import java.net.URI;

public class LocalDemoListingMediaStorage implements ListingMediaStorage {

    private static final String BUCKET = "listing-media-local";

    @Override
    public String bucket() {
        return BUCKET;
    }

    @Override
    public StorageUploadTarget createUploadTarget(String objectKey, String contentType, long sizeBytes) {
        return new StorageUploadTarget(BUCKET, objectKey, "LOCAL_DEMO", localUri(objectKey));
    }

    @Override
    public void verifyUploaded(String objectKey, String expectedContentType, long expectedSizeBytes) {
        // Local demo mode preserves older metadata-only behavior for offline development.
    }

    @Override
    public URI createReadUri(String objectKey) {
        return URI.create(localUri(objectKey));
    }

    private String localUri(String objectKey) {
        return "local-demo://" + BUCKET + "/" + objectKey;
    }
}
