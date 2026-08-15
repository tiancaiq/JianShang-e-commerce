package com.msb.ecom.product_service.search;

public class ListingSearchVectorMalformedReceiptException extends RuntimeException {

    public ListingSearchVectorMalformedReceiptException() {
        super("Listing search vector receipt is incompatible.");
    }
}
