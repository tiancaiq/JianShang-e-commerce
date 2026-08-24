package com.msb.ecom.product_service.operations;

import com.msb.ecom.product_service.search.ListingSearchProjectionSyncProperties;
import com.msb.ecom.product_service.search.ListingSearchVectorSyncProperties;
import com.msb.ecom.product_service.search.operator.ListingSearchOperatorProperties;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.msb.ecom.product_service.operations.ProductOperationsContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProductOperationsServiceTests {
    private final ProductOperationsRepository repository = mock(ProductOperationsRepository.class);
    private final ListingSearchProjectionSyncProperties projection = mock(ListingSearchProjectionSyncProperties.class);
    private final ListingSearchVectorSyncProperties vector = mock(ListingSearchVectorSyncProperties.class);
    private final ListingSearchOperatorProperties operator = mock(ListingSearchOperatorProperties.class);
    private final UlidGenerator ids = mock(UlidGenerator.class);
    private final Instant now = Instant.parse("2026-08-20T00:00:00Z");
    private ProductOperationsService service;

    @BeforeEach
    void setUp() {
        when(projection.effectiveMaxAttempts()).thenReturn(10);
        when(vector.effectiveMaxAttempts()).thenReturn(10);
        service = new ProductOperationsService(repository, projection, vector, operator, ids,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void dryRunValidatesRetryabilityWithoutChangingWorkerState() {
        Job failed = new Job("work-1", "LISTING_SEARCH_PROJECTION", "PRODUCT", "FAILED",
                2, 10, now, now, now, "correlation-1", "SAFE_CODE", "Safe summary", true,
                "LISTING", "listing-1");
        when(repository.job("work-1", now, 10, 10)).thenReturn(Optional.of(failed));

        Action preview = service.action(new ActionRequest("JOB", "work-1", "RETRY_JOB", true,
                "Retry failed projection work", "correlation-2"));

        assertThat(preview.allowed()).isTrue();
        assertThat(preview.result()).isNull();
        verify(repository, never()).retryJob(anyString(), any());
    }

    @Test
    void retryUsesExistingQueueAndPreservesAttemptHistoryContract() {
        Job failed = new Job("work-1", "LISTING_SEARCH_PROJECTION", "PRODUCT", "FAILED",
                2, 10, now, now, now, "correlation-1", "SAFE_CODE", "Safe summary", true,
                "LISTING", "listing-1");
        when(repository.job("work-1", now, 10, 10)).thenReturn(Optional.of(failed));
        when(repository.retryJob("work-1", now)).thenReturn(true);

        Action result = service.action(new ActionRequest("JOB", "work-1", "RETRY_JOB", false,
                "Retry failed projection work", "correlation-2"));

        assertThat(result.result()).isEqualTo("ACCEPTED");
        assertThat(result.warnings()).anyMatch(value -> value.contains("Attempt history is preserved"));
        verify(repository).retryJob("work-1", now);
    }

    @Test
    void oneListingReindexRereadsAuthoritativeStateAndQueuesOnlyProjectionWork() {
        ProductOperationsRepository.ListingState listing =
                new ProductOperationsRepository.ListingState("listing-1", 7, "INDIVIDUAL",
                        "ACTIVE", "APPROVED", null);
        when(projection.enabled()).thenReturn(true);
        when(repository.listingForUpdate("listing-1")).thenReturn(Optional.of(listing));
        when(ids.next()).thenReturn("work-new");

        Action result = service.action(new ActionRequest("SEARCH_LISTING", "listing-1",
                "REINDEX_LISTING", false, "Repair one listing projection", "correlation-3"));

        assertThat(result.result()).isEqualTo("ACCEPTED");
        assertThat(result.customerImpact()).contains("remain unchanged");
        verify(repository).reindex(listing, "work-new", "correlation-3", now);
        verify(repository).listingForUpdate("listing-1");
        verify(repository, never()).retryJob(anyString(), any());
        verify(repository, never()).retryOutbox(anyString(), any());
    }
}
