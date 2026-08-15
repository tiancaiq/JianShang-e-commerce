package com.msb.ecom.product_service.search;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListingVectorBackfillServiceTests {

    private final ListingVectorBackfillSnapshotReader reader =
            mock(ListingVectorBackfillSnapshotReader.class);
    private final OpenSearchListingVectorBackfillClient client =
            mock(OpenSearchListingVectorBackfillClient.class);

    @Test
    void disabledGateStopsBeforeMysqlAndOpenSearch() {
        when(client.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service(false).rebuildInactiveGeneration())
                .isInstanceOf(ListingSearchUnavailableException.class);

        verify(reader, never()).read(org.mockito.ArgumentMatchers.any());
        verify(client, never()).backfill(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void returnsOnlyInactiveValidatedBoundedCounts() {
        when(client.enabled()).thenReturn(true);
        when(reader.read(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ListingVectorBackfillSnapshotReader.Snapshot(List.of(), 2));
        when(client.backfill(List.of()))
                .thenReturn(new OpenSearchListingVectorBackfillClient.InactiveGeneration(
                        "marketplace-listings-v2-g20260723100000000", 5, 3));

        ListingVectorBackfillResult result = service(true).rebuildInactiveGeneration();

        assertThat(result.schemaVersion())
                .isEqualTo("MARKETPLACE_LISTING_VECTOR_BACKFILL_RESULT_V1");
        assertThat(result.status()).isEqualTo("INACTIVE_VALIDATED");
        assertThat(result.documentCount()).isEqualTo(5);
        assertThat(result.vectorDocumentCount()).isEqualTo(3);
        assertThat(result.lexicalOnlyDocumentCount()).isEqualTo(2);
        assertThat(result.rejectedReceiptCount()).isEqualTo(2);
    }

    private ListingVectorBackfillService service(boolean enabled) {
        Instant instant = Instant.parse("2026-07-23T10:00:00Z");
        return new ListingVectorBackfillService(
                new ListingVectorBackfillProperties(
                        enabled,
                        "marketplace-listings-v2",
                        100,
                        1000,
                        5_000_000,
                        true),
                reader,
                client,
                Clock.fixed(instant, ZoneOffset.UTC));
    }
}
