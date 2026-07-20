package com.msb.ecom.product_service.model;

public class BusinessSkuConflictException extends RuntimeException {

    public BusinessSkuConflictException() {
        super("This store already has an item with that SKU. Edit the existing item or use a different SKU.");
    }
}
