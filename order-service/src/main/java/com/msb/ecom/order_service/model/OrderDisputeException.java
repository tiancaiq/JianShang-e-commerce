package com.msb.ecom.order_service.model;

import org.springframework.http.HttpStatus;

public class OrderDisputeException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public OrderDisputeException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static OrderDisputeException notFound() {
        return new OrderDisputeException(HttpStatus.NOT_FOUND, "DISPUTE_NOT_FOUND", "Dispute was not found.");
    }
}
