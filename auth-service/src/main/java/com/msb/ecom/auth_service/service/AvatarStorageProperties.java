package com.msb.ecom.auth_service.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "user.avatar")
public record AvatarStorageProperties(
        String storage,
        String storageDir,
        long maxSizeBytes,
        Duration signedUrlTtl,
        S3Properties s3
) {

    public record S3Properties(
            String endpointUrl,
            String region,
            String bucket,
            String accessKeyId,
            String secretAccessKey,
            boolean pathStyleAccess
    ) {
    }
}
