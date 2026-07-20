package com.msb.ecom.order_service.model;

import org.springframework.http.HttpStatus;

public class BuyerOrderException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BuyerOrderException(HttpStatus status, String code, String message) {
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
