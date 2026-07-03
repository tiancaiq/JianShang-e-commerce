package com.msb.ecom.auth_service.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

public interface AvatarStorageService {

    String bucket();

    AvatarUploadTarget createUploadTarget(String userId, String contentType, long sizeBytes);

    void store(String userId, MultipartFile file);

    void uploadObject(String userId, String contentType, byte[] bytes);

    void verifyUploaded(String userId, String objectKey, String expectedContentType, long expectedSizeBytes);

    Optional<AvatarContent> read(String userId);

    void delete(String userId);

    void deleteOtherContentTypes(String userId, String contentType);
}
