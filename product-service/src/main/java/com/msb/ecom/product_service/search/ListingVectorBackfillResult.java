package com.msb.ecom.product_service.search;

public record ListingVectorBackfillResult(
        String schemaVersion,
        String status,
        String generation,
        int documentCount,
        int vectorDocumentCount,
        int lexicalOnlyDocumentCount,
        int rejectedReceiptCount,
        long elapsedMillis
) {

    public static final String SCHEMA_VERSION = "MARKETPLACE_LISTING_VECTOR_BACKFILL_RESULT_V1";
    public static final String STATUS = "INACTIVE_VALIDATED";
}
