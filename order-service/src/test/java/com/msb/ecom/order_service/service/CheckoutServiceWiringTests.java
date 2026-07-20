package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.repository.CartRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CheckoutService.class)
class CheckoutServiceWiringTests {

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    CartRepository cartRepository;

    @MockitoBean
    CartAssessmentService assessmentService;

    @MockitoBean
    BuyerIdentityClient buyerIdentityClient;

    @MockitoBean
    CheckoutCalculationService calculationService;

    @MockitoBean
    InventoryReservationClient inventoryClient;

    @MockitoBean
    CheckoutRepository checkoutRepository;

    @MockitoBean
    CheckoutProperties checkoutProperties;

    @MockitoBean
    CheckoutUlidGenerator checkoutUlidGenerator;

    @MockitoBean
    CheckoutMetrics checkoutMetrics;

    @MockitoBean
    ObjectMapper objectMapper;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    CheckoutService checkoutService;

    @Test
    void springSelectsProductionConstructorAndCreatesCheckoutService() {
        assertThat(checkoutService).isNotNull();
    }
}
