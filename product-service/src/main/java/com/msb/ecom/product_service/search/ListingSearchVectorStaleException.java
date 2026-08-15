package com.msb.ecom.product_service.search;

public class ListingSearchVectorStaleException extends RuntimeException {

    public ListingSearchVectorStaleException() {
        super("Listing search vector work is no longer current.");
    }
}
