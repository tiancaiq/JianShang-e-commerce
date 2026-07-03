package com.msb.ecom.auth_service.service;

import com.msb.ecom.common.storage.object.ObjectStorageAccessDeniedException;
import com.msb.ecom.common.storage.object.ObjectStorageException;
import com.msb.ecom.common.storage.object.ObjectStorageNotFoundException;
import com.msb.ecom.common.storage.object.ObjectStorageUploadTarget;
import com.msb.ecom.common.storage.object.ObjectStorageVerificationMessages;
import com.msb.ecom.common.storage.object.S3ObjectStorageClient;
import com.msb.ecom.common.storage.object.S3ObjectStorageSettings;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

public class S3AvatarStorageService implements AvatarStorageService, AutoCloseable {

    private static final ObjectStorageVerificationMessages VERIFY_MESSAGES = new ObjectStorageVerificationMessages(
            "Uploaded avatar size does not match upload request.",
            "Uploaded avatar content type does not match upload request.",
            "Uploaded avatar object was not found in storage.",
            "Avatar image could not be verified.");

    private final S3ObjectStorageClient storageClient;
    private final long maxSizeBytes;

    public S3AvatarStorageService(AvatarStorageProperties properties) {
        AvatarStorageProperties.S3Properties s3 = properties.s3();
        this.maxSizeBytes = properties.maxSizeBytes();
        this.storageClient = new S3ObjectStorageClient(new S3ObjectStorageSettings(
                s3.endpointUrl(),
                s3.region(),
                s3.bucket(),
                s3.accessKeyId(),
                s3.secretAccessKey(),
                s3.pathStyleAccess(),
                properties.signedUrlTtl(),
                "user avatar"));
    }

    @Override
    public String bucket() {
        return storageClient.bucket();
    }

    @Override
    public AvatarUploadTarget createUploadTarget(String userId, String contentType, long sizeBytes) {
        String normalizedContentType = AvatarStorageSupport.normalizedContentType(contentType);
        String extension = AvatarStorageSupport.extensionForContentType(normalizedContentType);
        AvatarStorageSupport.validateSize(sizeBytes, maxSizeBytes);
        String objectKey = AvatarStorageSupport.objectKey(userId, extension);

        ObjectStorageUploadTarget target = storageClient.createUploadTarget(objectKey, normalizedContentType);
        return new AvatarUploadTarget(target.bucket(), target.objectKey(), target.uploadMethod(), target.uploadUrl());
    }

    @Override
    public void store(String userId, MultipartFile file) {
        String contentType = AvatarStorageSupport.normalizedContentType(file);
        String extension = AvatarStorageSupport.extensionForContentType(contentType);
        AvatarStorageSupport.validateFile(file, maxSizeBytes);

        try {
            delete(userId);
            storageClient.putObject(AvatarStorageSupport.objectKey(userId, extension), contentType, file.getBytes());
        } catch (IOException exception) {
            throw new AvatarStorageException("Avatar image could not be stored.", exception);
        } catch (ObjectStorageException exception) {
            throw new AvatarStorageException("Avatar image could not be stored.", exception);
        }
    }

    @Override
    public void uploadObject(String userId, String contentType, byte[] bytes) {
        String normalizedContentType = AvatarStorageSupport.normalizedContentType(contentType);
        String extension = AvatarStorageSupport.extensionForContentType(normalizedContentType);
        AvatarStorageSupport.validateBytes(bytes, maxSizeBytes);
        try {
            storageClient.putObject(AvatarStorageSupport.objectKey(userId, extension), normalizedContentType, bytes);
        } catch (ObjectStorageException exception) {
            throw new AvatarStorageException("Avatar image could not be stored.", exception);
        }
    }

    @Override
    public void verifyUploaded(String userId, String objectKey, String expectedContentType, long expectedSizeBytes) {
        String normalizedContentType = AvatarStorageSupport.normalizedContentType(expectedContentType);
        String extension = AvatarStorageSupport.extensionForContentType(normalizedContentType);
        AvatarStorageSupport.validateSize(expectedSizeBytes, maxSizeBytes);
        AvatarStorageSupport.requireExpectedObjectKey(userId, objectKey, extension);
        try {
            storageClient.verifyUploaded(objectKey, normalizedContentType, expectedSizeBytes, VERIFY_MESSAGES);
        } catch (ObjectStorageException exception) {
            throw new AvatarStorageException(VERIFY_MESSAGES.unavailable(), exception);
        }
    }

    @Override
    public Optional<AvatarContent> read(String userId) {
        for (Map.Entry<String, String> entry : AvatarStorageSupport.CONTENT_TYPES_BY_EXTENSION.entrySet()) {
            String objectKey = AvatarStorageSupport.objectKey(userId, entry.getKey());
            Optional<byte[]> bytes = readObject(objectKey);
            if (bytes.isPresent()) {
                return Optional.of(new AvatarContent(entry.getValue(), bytes.get()));
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(String userId) {
        for (String extension : AvatarStorageSupport.CONTENT_TYPES_BY_EXTENSION.keySet()) {
            deleteObject(AvatarStorageSupport.objectKey(userId, extension));
        }
    }

    @Override
    public void deleteOtherContentTypes(String userId, String contentType) {
        String keepExtension = AvatarStorageSupport.extensionForContentType(
                AvatarStorageSupport.normalizedContentType(contentType));
        for (String extension : AvatarStorageSupport.CONTENT_TYPES_BY_EXTENSION.keySet()) {
            if (!extension.equals(keepExtension)) {
                deleteObject(AvatarStorageSupport.objectKey(userId, extension));
            }
        }
    }

    private Optional<byte[]> readObject(String objectKey) {
        try {
            return Optional.of(storageClient.readObject(objectKey));
        } catch (ObjectStorageNotFoundException exception) {
            return Optional.empty();
        } catch (ObjectStorageAccessDeniedException exception) {
            throw new AvatarStorageException("Avatar image could not be read.", exception);
        } catch (ObjectStorageException exception) {
            throw new AvatarStorageException("Avatar image could not be read.", exception);
        }
    }

    private void deleteObject(String objectKey) {
        try {
            storageClient.deleteObjectIfExists(objectKey);
        } catch (ObjectStorageException exception) {
            throw new AvatarStorageException("Avatar image could not be deleted.", exception);
        }
    }

    @Override
    public void close() {
        storageClient.close();
    }
}
