package com.msb.ecom.product_service.storage;

public class StorageObjectNotFoundException extends RuntimeException {

    public StorageObjectNotFoundException(String message) {
        super(message);
    }
}
