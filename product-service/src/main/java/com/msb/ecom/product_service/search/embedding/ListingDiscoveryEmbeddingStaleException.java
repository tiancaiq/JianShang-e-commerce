package com.msb.ecom.product_service.search.embedding;

public class ListingDiscoveryEmbeddingStaleException extends RuntimeException {

    public ListingDiscoveryEmbeddingStaleException() {
        super("Listing discovery embedding result is stale.");
    }
}
