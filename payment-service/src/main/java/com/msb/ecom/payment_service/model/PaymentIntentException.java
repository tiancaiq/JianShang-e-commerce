package com.msb.ecom.payment_service.model;

import org.springframework.http.HttpStatus;

public class PaymentIntentException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public PaymentIntentException(HttpStatus status, String code, String message) {
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
