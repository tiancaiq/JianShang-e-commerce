package com.msb.ecom.product_service.search.operator;

public final class ListingSearchOperatorConflictException extends RuntimeException {
    private final String code;

    public ListingSearchOperatorConflictException(String code) {
        super("Listing search rebuild state conflicts with this command.");
        this.code = code;
    }

    public String code() {
        return code;
    }
}
