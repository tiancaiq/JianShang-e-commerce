package com.msb.ecom.auth_service.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class LocalAvatarStorageService implements AvatarStorageService {

    private static final String BUCKET = "local-demo-user-avatars";

    private final Path storageDirectory;
    private final long maxSizeBytes;

    public LocalAvatarStorageService(String storageDirectory, long maxSizeBytes) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public String bucket() {
        return BUCKET;
    }

    @Override
    public AvatarUploadTarget createUploadTarget(String userId, String contentType, long sizeBytes) {
        String normalizedContentType = AvatarStorageSupport.normalizedContentType(contentType);
        AvatarStorageSupport.extensionForContentType(normalizedContentType);
        AvatarStorageSupport.validateSize(sizeBytes, maxSizeBytes);
        return new AvatarUploadTarget(
                BUCKET,
                AvatarStorageSupport.objectKey(userId, AvatarStorageSupport.extensionForContentType(normalizedContentType)),
                "PUT",
                "/api/v1/users/me/avatar/content");
    }

    @Override
    // Stores only validated avatar image bytes under a server-owned path; callers expose app URLs, never file paths.
    public void store(String userId, MultipartFile file) {
        String contentType = AvatarStorageSupport.normalizedContentType(file);
        String extension = AvatarStorageSupport.extensionForContentType(contentType);
        AvatarStorageSupport.validateFile(file, maxSizeBytes);

        try {
            Files.createDirectories(storageDirectory);
            delete(userId);
            Files.write(avatarPath(userId, extension), file.getBytes());
        } catch (IOException exception) {
            throw new AvatarStorageException("Avatar image could not be stored.", exception);
        }
    }

    @Override
    public void uploadObject(String userId, String contentType, byte[] bytes) {
        String normalizedContentType = AvatarStorageSupport.normalizedContentType(contentType);
        String extension = AvatarStorageSupport.extensionForContentType(normalizedContentType);
        AvatarStorageSupport.validateBytes(bytes, maxSizeBytes);
        try {
            Files.createDirectories(storageDirectory);
            Files.write(avatarPath(userId, extension), bytes);
        } catch (IOException exception) {
            throw new AvatarStorageException("Avatar image could not be stored.", exception);
        }
    }

    @Override
    public void verifyUploaded(String userId, String objectKey, String expectedContentType, long expectedSizeBytes) {
        String normalizedContentType = AvatarStorageSupport.normalizedContentType(expectedContentType);
        String extension = AvatarStorageSupport.extensionForContentType(normalizedContentType);
        AvatarStorageSupport.validateSize(expectedSizeBytes, maxSizeBytes);
        AvatarStorageSupport.requireExpectedObjectKey(userId, objectKey, extension);
        Path path = avatarPath(userId, extension);
        try {
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Uploaded avatar object was not found in storage.");
            }
            if (Files.size(path) != expectedSizeBytes) {
                throw new IllegalArgumentException("Uploaded avatar size does not match upload request.");
            }
        } catch (IOException exception) {
            throw new AvatarStorageException("Avatar image could not be verified.", exception);
        }
    }

    @Override
    public Optional<AvatarContent> read(String userId) {
        for (Map.Entry<String, String> entry : AvatarStorageSupport.CONTENT_TYPES_BY_EXTENSION.entrySet()) {
            Path path = avatarPath(userId, entry.getKey());
            if (!Files.exists(path)) {
                continue;
            }
            try {
                return Optional.of(new AvatarContent(entry.getValue(), Files.readAllBytes(path)));
            } catch (IOException exception) {
                throw new AvatarStorageException("Avatar image could not be read.", exception);
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(String userId) {
        for (String extension : AvatarStorageSupport.CONTENT_TYPES_BY_EXTENSION.keySet()) {
            try {
                Files.deleteIfExists(avatarPath(userId, extension));
            } catch (IOException exception) {
                throw new AvatarStorageException("Avatar image could not be deleted.", exception);
            }
        }
    }

    @Override
    public void deleteOtherContentTypes(String userId, String contentType) {
        String keepExtension = AvatarStorageSupport.extensionForContentType(
                AvatarStorageSupport.normalizedContentType(contentType));
        for (String extension : AvatarStorageSupport.CONTENT_TYPES_BY_EXTENSION.keySet()) {
            if (extension.equals(keepExtension)) {
                continue;
            }
            try {
                Files.deleteIfExists(avatarPath(userId, extension));
            } catch (IOException exception) {
                throw new AvatarStorageException("Avatar image could not be deleted.", exception);
            }
        }
    }

    private Path avatarPath(String userId, String extension) {
        return storageDirectory.resolve(userId + "." + extension).normalize();
    }
}
