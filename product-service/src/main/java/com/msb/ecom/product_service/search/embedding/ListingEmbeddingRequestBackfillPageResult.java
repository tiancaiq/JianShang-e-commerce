package com.msb.ecom.product_service.search.embedding;

record ListingEmbeddingRequestBackfillPageResult(
        int processed,
        int created,
        int alreadyPresent,
        int skipped,
        String lastProcessedListingId,
        boolean hasMore
) {
}
