package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutPaymentProperties;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CheckoutPaymentService.class)
class CheckoutPaymentServiceWiringTests {

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    BuyerIdentityClient buyerIdentityClient;

    @MockitoBean
    CheckoutRepository checkoutRepository;

    @MockitoBean
    CheckoutPaymentBindingRepository checkoutPaymentBindingRepository;

    @MockitoBean
    CheckoutProperties checkoutProperties;

    @MockitoBean
    CheckoutPaymentProperties checkoutPaymentProperties;

    @MockitoBean
    PaymentIntentClient paymentIntentClient;

    @Autowired
    CheckoutPaymentService checkoutPaymentService;

    @Test
    void springSelectsTheProductionConstructor() {
        assertThat(checkoutPaymentService).isNotNull();
    }
}
