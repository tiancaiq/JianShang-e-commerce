package com.msb.ecom.product_service.search;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ListingVectorBackfillService {

    private final ListingVectorBackfillProperties properties;
    private final ListingVectorBackfillSnapshotReader snapshotReader;
    private final OpenSearchListingVectorBackfillClient client;
    private final Clock clock;

    @Autowired
    public ListingVectorBackfillService(
            ListingVectorBackfillProperties properties,
            ListingVectorBackfillSnapshotReader snapshotReader,
            OpenSearchListingVectorBackfillClient client) {
        this(properties, snapshotReader, client, Clock.systemUTC());
    }

    ListingVectorBackfillService(
            ListingVectorBackfillProperties properties,
            ListingVectorBackfillSnapshotReader snapshotReader,
            OpenSearchListingVectorBackfillClient client,
            Clock clock) {
        this.properties = properties;
        this.snapshotReader = snapshotReader;
        this.client = client;
        this.clock = clock;
    }

    // Produces an offline-only validated generation and intentionally leaves both stable aliases untouched.
    public ListingVectorBackfillResult rebuildInactiveGeneration() {
        if (!properties.enabled() || !client.enabled()) {
            throw new ListingSearchUnavailableException("Listing vector backfill is disabled.");
        }
        Instant startedAt = clock.instant();
        ListingVectorBackfillSnapshotReader.Snapshot snapshot = snapshotReader.read(properties);
        OpenSearchListingVectorBackfillClient.InactiveGeneration generation =
                client.backfill(snapshot.documents());
        return new ListingVectorBackfillResult(
                ListingVectorBackfillResult.SCHEMA_VERSION,
                ListingVectorBackfillResult.STATUS,
                generation.generation(),
                generation.documentCount(),
                generation.vectorDocumentCount(),
                generation.documentCount() - generation.vectorDocumentCount(),
                snapshot.rejectedReceiptCount(),
                Math.max(0L, Duration.between(startedAt, clock.instant()).toMillis()));
    }
}
