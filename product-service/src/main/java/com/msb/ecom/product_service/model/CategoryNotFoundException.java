package com.msb.ecom.product_service.model;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException() {
        super("Category was not found.");
    }
}
