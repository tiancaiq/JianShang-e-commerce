package com.msb.ecom.payment_service.outbox;

import com.msb.ecom.payment_service.config.PaymentOutboxProperties;
import com.msb.ecom.payment_service.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(PaymentOutboxDispatcher.class)
class PaymentOutboxDispatcherWiringTests {

    @MockitoBean
    PaymentOutboxRepository repository;

    @MockitoBean
    PaymentOutboxMessageFactory messageFactory;

    @MockitoBean
    PaymentEventTransport transport;

    @MockitoBean
    PaymentOutboxProperties properties;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    PaymentOutboxDispatcher dispatcher;

    @Test
    void springSelectsTheProductionConstructor() {
        assertThat(dispatcher).isNotNull();
    }
}
