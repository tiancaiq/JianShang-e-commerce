package com.msb.ecom.product_service.search.operator;

public final class ListingSearchOperatorNotFoundException extends RuntimeException {
    public ListingSearchOperatorNotFoundException() {
        super("Listing search rebuild was not found.");
    }
}
