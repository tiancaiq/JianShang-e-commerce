package com.msb.ecom.product_service.listing;

public class ListingNotFoundException extends RuntimeException {

    public ListingNotFoundException() {
        super("Listing was not found.");
    }
}
