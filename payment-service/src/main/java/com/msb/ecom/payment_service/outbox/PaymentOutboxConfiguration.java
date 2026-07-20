package com.msb.ecom.payment_service.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentOutboxConfiguration {

    @Bean
    @ConditionalOnMissingBean(PaymentEventTransport.class)
    // Keeps dispatch fail-closed until an approved transport adapter is installed.
    PaymentEventTransport unavailablePaymentEventTransport() {
        return new PaymentEventTransport() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public void publish(PaymentEventMessage message) {
                throw new PaymentEventTransportException("TRANSPORT_NOT_CONFIGURED");
            }
        };
    }
}
