package com.msb.ecom.order_service.model;

public class OrderConfirmationException extends RuntimeException {

    private final String code;

    public OrderConfirmationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
