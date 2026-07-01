package com.msb.ecom.product_service.model;

public class ListingMediaAccessDeniedException extends RuntimeException {

    public ListingMediaAccessDeniedException() {
        super("Listing media storage access was denied.");
    }

    public ListingMediaAccessDeniedException(String message) {
        super(message);
    }
}
