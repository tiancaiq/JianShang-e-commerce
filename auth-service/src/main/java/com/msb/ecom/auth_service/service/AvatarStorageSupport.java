package com.msb.ecom.auth_service.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

final class AvatarStorageSupport {

    static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    static final Map<String, String> CONTENT_TYPES_BY_EXTENSION = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    private AvatarStorageSupport() {
    }

    static String normalizedContentType(MultipartFile file) {
        String contentType = file.getContentType();
        return normalizedContentType(contentType);
    }

    static String normalizedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("Avatar image content type is required.");
        }
        String normalized = contentType.toLowerCase();
        int parameterStart = normalized.indexOf(';');
        if (parameterStart >= 0) {
            normalized = normalized.substring(0, parameterStart).trim();
        }
        return normalized;
    }

    static String extensionForContentType(String contentType) {
        String extension = EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("Avatar image must be a PNG, JPEG, or WebP file.");
        }
        return extension;
    }

    static void validateFile(MultipartFile file, long maxSizeBytes) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Avatar image must not be empty.");
        }
        validateSize(file.getSize(), maxSizeBytes);
    }

    static void validateBytes(byte[] bytes, long maxSizeBytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Avatar image must not be empty.");
        }
        validateSize(bytes.length, maxSizeBytes);
    }

    static void validateSize(long sizeBytes, long maxSizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Avatar image size is required.");
        }
        if (sizeBytes > maxSizeBytes) {
            throw new IllegalArgumentException("Avatar image is too large.");
        }
    }

    static String objectKey(String userId, String extension) {
        return "users/" + userId + "/avatar." + extension;
    }

    static void requireExpectedObjectKey(String userId, String objectKey, String extension) {
        String expected = objectKey(userId, extension);
        if (!expected.equals(objectKey)) {
            throw new IllegalArgumentException("Uploaded avatar object key does not match upload request.");
        }
    }
}
