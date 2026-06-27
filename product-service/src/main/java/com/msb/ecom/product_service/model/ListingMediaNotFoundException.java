package com.msb.ecom.product_service.model;

public class ListingMediaNotFoundException extends RuntimeException {

    public ListingMediaNotFoundException() {
        super("Listing media was not found.");
    }
}
