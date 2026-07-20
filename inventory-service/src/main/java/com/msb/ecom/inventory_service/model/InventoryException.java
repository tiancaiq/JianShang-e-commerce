package com.msb.ecom.inventory_service.model;

import org.springframework.http.HttpStatus;

public final class InventoryException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public InventoryException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
