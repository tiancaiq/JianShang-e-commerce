package com.msb.ecom.payment_service.outbox;

public interface PaymentEventTransport {

    // Reports whether dispatch may be safely enabled for this transport.
    boolean available();

    // Publishes one stable payment event and returns only after acknowledgement.
    void publish(PaymentEventMessage message);
}
