package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BuyerOrderProperties;
import com.msb.ecom.order_service.repository.BuyerOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(BuyerOrderService.class)
class BuyerOrderServiceWiringTests {

    @MockitoBean
    BuyerOrderProperties properties;

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    BuyerIdentityClient buyerIdentityClient;

    @MockitoBean
    BuyerOrderRepository repository;

    @MockitoBean
    BuyerOrderMetrics metrics;

    @Autowired
    BuyerOrderService service;

    @Test
    void springUsesTheProductionBuyerOrderConstructor() {
        assertThat(service).isNotNull();
    }
}
