package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderConfirmationProperties;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.OrderConfirmationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(PaymentSucceededOrderConfirmationHandler.class)
class PaymentSucceededOrderConfirmationWiringTests {

    @MockitoBean
    OrderConfirmationProperties properties;
    @MockitoBean
    PaymentEventValidator validator;
    @MockitoBean
    CheckoutRepository checkouts;
    @MockitoBean
    CheckoutPaymentBindingRepository bindings;
    @MockitoBean
    OrderConfirmationRepository orders;
    @MockitoBean
    InventoryReservationClient inventory;
    @MockitoBean
    CheckoutUlidGenerator ids;
    @MockitoBean
    ObjectMapper objectMapper;
    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    PaymentSucceededOrderConfirmationHandler handler;

    @Test
    void springSelectsTheProductionConstructor() {
        assertThat(handler).isNotNull();
    }
}
