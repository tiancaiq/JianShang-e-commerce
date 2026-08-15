package com.msb.ecom.product_service.search.embedding;

public class ListingDiscoveryEmbeddingIdempotencyConflictException extends RuntimeException {

    public ListingDiscoveryEmbeddingIdempotencyConflictException() {
        super("Listing discovery embedding result conflicts with the accepted result.");
    }
}
