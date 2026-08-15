package com.msb.ecom.product_service.search.embedding;

public class ListingDiscoveryEmbeddingSourceNotFoundException extends RuntimeException {

    public ListingDiscoveryEmbeddingSourceNotFoundException() {
        super("Listing discovery embedding source was not found.");
    }
}
