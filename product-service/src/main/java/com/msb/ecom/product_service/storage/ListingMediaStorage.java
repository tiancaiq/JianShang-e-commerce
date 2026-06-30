package com.msb.ecom.product_service.storage;

import java.net.URI;

public interface ListingMediaStorage {

    String bucket();

    StorageUploadTarget createUploadTarget(String objectKey, String contentType, long sizeBytes);

    void uploadObject(String objectKey, String contentType, byte[] bytes);

    void verifyUploaded(String objectKey, String expectedContentType, long expectedSizeBytes);

    URI createReadUri(String objectKey);
}
