package com.msb.ecom.common.storage.object;

import java.time.Duration;

public record S3ObjectStorageSettings(
        String endpointUrl,
        String region,
        String bucket,
        String accessKeyId,
        String secretAccessKey,
        boolean pathStyleAccess,
        Duration signedUrlTtl,
        String contextName
) {

    String requiredRegion() {
        return required("S3 region", region);
    }

    String requiredBucket() {
        return required("S3 bucket", bucket);
    }

    String requiredAccessKeyId() {
        return required("S3 access key", accessKeyId);
    }

    String requiredSecretAccessKey() {
        return required("S3 secret key", secretAccessKey);
    }

    private String required(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is required when " + contextName + " storage is s3.");
        }
        return value.trim();
    }
}
