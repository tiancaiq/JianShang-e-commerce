package com.msb.ecom.product_service.model;

public class ListingNotFoundException extends RuntimeException {

    public ListingNotFoundException() {
        super("Listing was not found.");
    }
}
