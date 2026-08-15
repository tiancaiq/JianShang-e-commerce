package com.msb.ecom.product_service.search.embedding;

public class ListingDiscoveryEmbeddingFeatureDisabledException extends RuntimeException {

    public ListingDiscoveryEmbeddingFeatureDisabledException() {
        super("Listing discovery embedding source is disabled.");
    }
}
