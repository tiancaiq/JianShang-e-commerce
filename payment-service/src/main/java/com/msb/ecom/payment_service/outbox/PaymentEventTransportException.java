package com.msb.ecom.payment_service.outbox;

public class PaymentEventTransportException extends RuntimeException {

    private final String safeCode;

    public PaymentEventTransportException(String safeCode) {
        super("Payment event transport failed.");
        this.safeCode = safeCode;
    }

    public String safeCode() {
        return safeCode;
    }
}
