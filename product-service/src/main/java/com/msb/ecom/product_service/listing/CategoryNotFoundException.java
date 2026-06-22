package com.msb.ecom.product_service.listing;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException() {
        super("Category was not found.");
    }
}
