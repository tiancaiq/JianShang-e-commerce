package com.msb.ecom.product_service.storage;

public interface ListingMediaStorage {

    String bucket();

    StorageUploadTarget createUploadTarget(String objectKey, String contentType, long sizeBytes);

    void uploadObject(String objectKey, String contentType, byte[] bytes);

    void verifyUploaded(String objectKey, String expectedContentType, long expectedSizeBytes);

    byte[] readObject(String objectKey);

    void verifyReadable(String objectKey);
}
