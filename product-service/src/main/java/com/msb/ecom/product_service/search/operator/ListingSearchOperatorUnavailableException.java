package com.msb.ecom.product_service.search.operator;

public final class ListingSearchOperatorUnavailableException extends RuntimeException {
    public ListingSearchOperatorUnavailableException() {
        super("Listing search operator command is unavailable.");
    }

    public ListingSearchOperatorUnavailableException(Throwable cause) {
        super("Listing search operator command is unavailable.", cause);
    }
}
