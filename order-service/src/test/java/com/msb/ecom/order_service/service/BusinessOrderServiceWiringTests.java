package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderProperties;
import com.msb.ecom.order_service.repository.BusinessOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(BusinessOrderService.class)
class BusinessOrderServiceWiringTests {

    @MockitoBean
    BusinessOrderProperties properties;

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    BusinessOrderAuthorizationClient authorizationClient;

    @MockitoBean
    BusinessOrderRepository repository;

    @MockitoBean
    BusinessOrderMetrics metrics;

    @Autowired
    BusinessOrderService service;

    @Test
    void springUsesTheProductionBusinessOrderConstructor() {
        assertThat(service).isNotNull();
    }
}
