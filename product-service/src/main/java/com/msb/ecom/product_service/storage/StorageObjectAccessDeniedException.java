package com.msb.ecom.product_service.storage;

public class StorageObjectAccessDeniedException extends RuntimeException {

    public StorageObjectAccessDeniedException(String message) {
        super(message);
    }
}
