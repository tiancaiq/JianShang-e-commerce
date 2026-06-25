package com.msb.ecom.product_service.listing;

public class ListingMediaNotFoundException extends RuntimeException {

    public ListingMediaNotFoundException() {
        super("Listing media was not found.");
    }
}
