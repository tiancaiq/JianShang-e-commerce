package com.msb.ecom.order_service.model;

import org.springframework.http.HttpStatus;

public final class CartException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CartException(HttpStatus status, String code, String message) {
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
