package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProcessingProperties;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.repository.AdminOrderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig({AdminOrderService.class, AdminOrderCancellationService.class})
class AdminOrderServiceWiringTests {

    @MockitoBean
    CurrentActorProvider actors;

    @MockitoBean
    AdminOrderAuthorizationClient authorization;

    @MockitoBean
    AdminOrderRepository repository;

    @MockitoBean
    AdminOrderListingContextClient listingContext;

    @MockitoBean
    PaymentIntentClient payments;

    @MockitoBean
    InventoryReservationClient inventory;

    @MockitoBean
    OrderCancellationProperties cancellationProperties;

    @MockitoBean
    OrderCancellationProcessingProperties processingProperties;

    @MockitoBean
    CheckoutUlidGenerator ids;

    @MockitoBean
    ObjectMapper objectMapper;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    AdminOrderService service;

    @Autowired
    AdminOrderCancellationService cancellationService;

    @Test
    void springUsesTheProductionAdminOrderConstructors() {
        assertThat(service).isNotNull();
        assertThat(cancellationService).isNotNull();
    }
}
