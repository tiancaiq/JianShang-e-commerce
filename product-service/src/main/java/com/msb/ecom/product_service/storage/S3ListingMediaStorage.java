package com.msb.ecom.product_service.storage;

import com.msb.ecom.common.storage.object.ObjectStorageAccessDeniedException;
import com.msb.ecom.common.storage.object.ObjectStorageException;
import com.msb.ecom.common.storage.object.ObjectStorageNotFoundException;
import com.msb.ecom.common.storage.object.ObjectStorageUploadTarget;
import com.msb.ecom.common.storage.object.ObjectStorageVerificationMessages;
import com.msb.ecom.common.storage.object.S3ObjectStorageClient;
import com.msb.ecom.common.storage.object.S3ObjectStorageSettings;

public class S3ListingMediaStorage implements ListingMediaStorage, AutoCloseable {

    private static final ObjectStorageVerificationMessages VERIFY_MESSAGES = new ObjectStorageVerificationMessages(
            "Uploaded object size does not match upload request.",
            "Uploaded object content type does not match upload request.",
            "Uploaded object was not found in storage.",
            "Uploaded object could not be verified.");

    private final S3ObjectStorageClient storageClient;

    public S3ListingMediaStorage(ListingMediaStorageProperties properties) {
        ListingMediaStorageProperties.S3Properties s3 = properties.s3();
        this.storageClient = new S3ObjectStorageClient(new S3ObjectStorageSettings(
                s3.endpointUrl(),
                s3.region(),
                s3.bucket(),
                s3.accessKeyId(),
                s3.secretAccessKey(),
                s3.pathStyleAccess(),
                properties.signedUrlTtl(),
                "listing media"));
    }

    @Override
    public String bucket() {
        return storageClient.bucket();
    }

    @Override
    public StorageUploadTarget createUploadTarget(String objectKey, String contentType, long sizeBytes) {
        ObjectStorageUploadTarget target = storageClient.createUploadTarget(objectKey, contentType);
        return new StorageUploadTarget(target.bucket(), target.objectKey(), target.uploadMethod(), target.uploadUrl());
    }

    @Override
    public void uploadObject(String objectKey, String contentType, byte[] bytes) {
        storageClient.putObject(objectKey, contentType, bytes);
    }

    @Override
    // Confirm verifies the object exists in storage before metadata can become UPLOADED.
    public void verifyUploaded(String objectKey, String expectedContentType, long expectedSizeBytes) {
        try {
            storageClient.verifyUploaded(objectKey, expectedContentType, expectedSizeBytes, VERIFY_MESSAGES);
        } catch (ObjectStorageException exception) {
            throw new IllegalArgumentException(VERIFY_MESSAGES.unavailable());
        }
    }

    @Override
    public byte[] readObject(String objectKey) {
        try {
            return storageClient.readObject(objectKey);
        } catch (ObjectStorageAccessDeniedException exception) {
            throw new StorageObjectAccessDeniedException(
                    exception.getMessage().replace("Stored object", "Stored media object"));
        } catch (ObjectStorageNotFoundException exception) {
            throw new StorageObjectNotFoundException(
                    exception.getMessage().replace("Stored object", "Stored media object"));
        }
    }

    @Override
    public void close() {
        storageClient.close();
    }
}
