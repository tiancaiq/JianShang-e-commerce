package com.msb.ecom.order_service.model;

import org.springframework.http.HttpStatus;

public class CheckoutException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String checkoutId;

    public CheckoutException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public CheckoutException(HttpStatus status, String code, String message, String checkoutId) {
        super(message);
        this.status = status;
        this.code = code;
        this.checkoutId = checkoutId;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String checkoutId() {
        return checkoutId;
    }
}
