package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderAcceptanceProperties;
import com.msb.ecom.order_service.repository.BusinessOrderAcceptanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(BusinessOrderAcceptanceService.class)
class BusinessOrderAcceptanceServiceWiringTests {

    @MockitoBean
    BusinessOrderAcceptanceProperties properties;

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    BusinessOrderAuthorizationClient authorizationClient;

    @MockitoBean
    BusinessOrderAcceptanceRepository repository;

    @MockitoBean
    BusinessOrderMetrics metrics;

    @MockitoBean
    CheckoutUlidGenerator ids;

    @MockitoBean
    ObjectMapper objectMapper;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    BusinessOrderAcceptanceService service;

    @Test
    void springUsesTheProductionAcceptanceConstructor() {
        assertThat(service).isNotNull();
    }
}
