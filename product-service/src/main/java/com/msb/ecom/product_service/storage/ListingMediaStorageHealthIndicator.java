package com.msb.ecom.product_service.storage;

import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class ListingMediaStorageHealthIndicator implements HealthIndicator {

    private static final String MODE_LOCAL_DEMO = "local-demo";
    private static final String MODE_S3 = "s3";
    private static final int MAX_PERSISTED_OBJECT_CANDIDATES = 25;

    private final ListingMediaStorage storage;
    private final ListingMediaStorageProperties properties;
    private final ListingMediaRepository mediaRepository;

    public ListingMediaStorageHealthIndicator(
            ListingMediaStorage storage,
            ListingMediaStorageProperties properties,
            ListingMediaRepository mediaRepository) {
        this.storage = storage;
        this.properties = properties;
        this.mediaRepository = mediaRepository;
    }

    @Override
    public Health health() {
        String mode = normalizedMode(properties.storage());
        if (MODE_LOCAL_DEMO.equals(mode)) {
            return Health.up()
                    .withDetail("mode", MODE_LOCAL_DEMO)
                    .withDetail("check", "metadata-only")
                    .build();
        }
        if (!MODE_S3.equals(mode)) {
            return down(mode, "UNSUPPORTED_MODE", "configuration");
        }

        try {
            String configuredObjectKey = trimmed(properties.healthCheckObjectKey());
            if (!configuredObjectKey.isEmpty()) {
                storage.verifyReadable(configuredObjectKey);
                return up(mode, "configured-object");
            }

            var objectKeys = mediaRepository.findStorageHealthCheckObjectKeys(MAX_PERSISTED_OBJECT_CANDIDATES);
            if (objectKeys.isEmpty()) {
                return down(mode, "NO_CHECK_OBJECT", "persisted-object");
            }
            for (String objectKey : objectKeys) {
                try {
                    storage.verifyReadable(objectKey);
                    return up(mode, "persisted-object");
                } catch (StorageObjectNotFoundException ignored) {
                    // Local-demo metadata can outlive its intentionally absent object bytes.
                }
            }
            return down(mode, "OBJECT_NOT_FOUND", "persisted-object");
        } catch (StorageObjectAccessDeniedException exception) {
            return down(mode, "ACCESS_DENIED", checkType());
        } catch (StorageObjectNotFoundException exception) {
            return down(mode, "OBJECT_NOT_FOUND", checkType());
        } catch (RuntimeException exception) {
            return down(mode, "STORAGE_UNAVAILABLE", checkType());
        }
    }

    private Health up(String mode, String check) {
        return Health.up()
                .withDetail("mode", mode)
                .withDetail("check", check)
                .build();
    }

    private Health down(String mode, String reason, String check) {
        return Health.down()
                .withDetail("mode", mode.isEmpty() ? "unset" : mode)
                .withDetail("check", check)
                .withDetail("reason", reason)
                .build();
    }

    private String checkType() {
        return trimmed(properties.healthCheckObjectKey()).isEmpty() ? "persisted-object" : "configured-object";
    }

    private String normalizedMode(String value) {
        return trimmed(value).toLowerCase();
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
