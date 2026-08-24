package com.msb.ecom.product_service.storage;

import com.msb.ecom.product_service.repository.ListingMediaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingMediaStorageHealthIndicatorTests {

    private final ListingMediaStorage storage = mock(ListingMediaStorage.class);
    private final ListingMediaRepository repository = mock(ListingMediaRepository.class);

    @Test
    void localDemoIsHealthyWithoutAccessingStorageOrMetadata() {
        Health health = indicator("local-demo", "").health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("metadata-only", health.getDetails().get("check"));
        verify(storage, never()).verifyReadable(org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).findStorageHealthCheckObjectKey();
    }

    @Test
    void s3UsesPersistedUploadedObjectWhenNoOverrideIsConfigured() {
        when(repository.findStorageHealthCheckObjectKey()).thenReturn(Optional.of("private/known-image.png"));

        Health health = indicator("s3", "").health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("persisted-object", health.getDetails().get("check"));
        verify(storage).verifyReadable("private/known-image.png");
        assertFalse(health.getDetails().containsValue("private/known-image.png"));
    }

    @Test
    void accessDeniedReturnsOnlyANonSecretReasonCode() {
        when(repository.findStorageHealthCheckObjectKey()).thenReturn(Optional.of("private/known-image.png"));
        org.mockito.Mockito.doThrow(new StorageObjectAccessDeniedException("provider detail"))
                .when(storage).verifyReadable("private/known-image.png");

        Health health = indicator("s3", "").health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("ACCESS_DENIED", health.getDetails().get("reason"));
        assertFalse(health.getDetails().toString().contains("provider detail"));
        assertFalse(health.getDetails().toString().contains("known-image"));
    }

    @Test
    void missingKnownObjectMakesS3DeploymentUnhealthy() {
        when(repository.findStorageHealthCheckObjectKey()).thenReturn(Optional.empty());

        Health health = indicator("s3", "").health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("NO_CHECK_OBJECT", health.getDetails().get("reason"));
        verify(storage, never()).verifyReadable(org.mockito.ArgumentMatchers.anyString());
    }

    private ListingMediaStorageHealthIndicator indicator(String mode, String objectKey) {
        ListingMediaStorageProperties properties = new ListingMediaStorageProperties(
                mode,
                10_485_760,
                Duration.ofMinutes(15),
                objectKey,
                new ListingMediaStorageProperties.S3Properties(
                        "https://storage.example.test",
                        "auto",
                        "private-bucket",
                        "access-key",
                        "secret-key",
                        true));
        return new ListingMediaStorageHealthIndicator(storage, properties, repository);
    }
}
