package com.msb.ecom.order_service.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(CartAssessmentService.class)
class CartAssessmentServiceWiringTests {

    @MockitoBean
    ProductCommerceClient productClient;

    @MockitoBean
    InventoryAvailabilityClient inventoryClient;

    @MockitoBean
    BusinessStoreEligibilityClient eligibilityClient;

    @Autowired
    CartAssessmentService cartAssessmentService;

    @Test
    void springSelectsProductionConstructorAndCreatesCartAssessmentService() {
        assertThat(cartAssessmentService).isNotNull();
    }
}
