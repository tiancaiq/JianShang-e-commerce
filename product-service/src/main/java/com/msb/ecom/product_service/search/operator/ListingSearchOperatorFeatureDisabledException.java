package com.msb.ecom.product_service.search.operator;

public final class ListingSearchOperatorFeatureDisabledException extends RuntimeException {
    public ListingSearchOperatorFeatureDisabledException() {
        super("Listing search operator boundary is disabled.");
    }
}
