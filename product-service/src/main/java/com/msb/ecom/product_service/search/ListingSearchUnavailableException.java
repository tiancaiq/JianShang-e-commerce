package com.msb.ecom.product_service.search;

public class ListingSearchUnavailableException extends RuntimeException {

    public ListingSearchUnavailableException(String message) {
        super(message);
    }

    public ListingSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
