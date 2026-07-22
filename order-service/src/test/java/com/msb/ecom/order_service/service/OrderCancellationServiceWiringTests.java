package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.repository.OrderCancellationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(OrderCancellationService.class)
class OrderCancellationServiceWiringTests {

    @MockitoBean
    OrderCancellationProperties properties;

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    BuyerIdentityClient buyerIdentityClient;

    @MockitoBean
    OrderCancellationRepository repository;

    @MockitoBean
    CheckoutUlidGenerator ids;

    @MockitoBean
    OrderCancellationMetrics metrics;

    @MockitoBean
    ObjectMapper objectMapper;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    OrderCancellationService service;

    @Test
    void springUsesTheProductionCancellationConstructor() {
        assertThat(service).isNotNull();
    }
}
