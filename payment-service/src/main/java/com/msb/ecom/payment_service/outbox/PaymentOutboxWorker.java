package com.msb.ecom.payment_service.outbox;

import com.msb.ecom.payment_service.config.PaymentOutboxProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOutboxWorker {

    private final PaymentOutboxDispatcher dispatcher;
    private final PaymentOutboxProperties properties;

    public PaymentOutboxWorker(
            PaymentOutboxDispatcher dispatcher,
            PaymentOutboxProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${payment.outbox.dispatch.interval-ms:1000}")
    // Leaves the scheduled boundary inert unless its independent worker gate is enabled.
    public void dispatch() {
        if (properties.workerEnabled()) {
            dispatcher.dispatchBatch();
        }
    }
}
