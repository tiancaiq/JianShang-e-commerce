package com.msb.ecom.product_service.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "listing.media")
public record ListingMediaStorageProperties(
        String storage,
        long maxImageSizeBytes,
        Duration signedUrlTtl,
        String healthCheckObjectKey,
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
