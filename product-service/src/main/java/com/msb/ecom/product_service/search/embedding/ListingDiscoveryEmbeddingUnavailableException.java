package com.msb.ecom.product_service.search.embedding;

public class ListingDiscoveryEmbeddingUnavailableException extends RuntimeException {

    public ListingDiscoveryEmbeddingUnavailableException() {
        super("Listing discovery embedding source is temporarily unavailable.");
    }
}
