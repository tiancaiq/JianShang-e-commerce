package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.RedisCartRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig({
        RedisCartRepository.class,
        CartService.class,
        CartAssessmentService.class,
        CartValidationService.class,
        CheckoutUlidGenerator.class,
        CheckoutCalculationService.class,
        CheckoutService.class,
        OrderServiceConstructorWiringTests.ConversionConfiguration.class
})
class OrderServiceConstructorWiringTests {

    @MockitoBean
    StringRedisTemplate redis;

    @MockitoBean
    ObjectMapper objectMapper;

    @MockitoBean
    CurrentActorProvider actorProvider;

    @MockitoBean
    ProductCommerceClient productClient;

    @MockitoBean
    InventoryAvailabilityClient inventoryAvailabilityClient;

    @MockitoBean
    BusinessStoreEligibilityClient businessStoreEligibilityClient;

    @MockitoBean
    CheckoutProperties checkoutProperties;

    @MockitoBean
    PlatformPolicyProvider platformPolicyProvider;

    @MockitoBean
    ShippingCalculationAdapter shippingCalculationAdapter;

    @MockitoBean
    TaxCalculationAdapter taxCalculationAdapter;

    @MockitoBean
    BuyerIdentityClient buyerIdentityClient;

    @MockitoBean
    InventoryReservationClient inventoryReservationClient;

    @MockitoBean
    CheckoutRepository checkoutRepository;

    @MockitoBean
    CheckoutMetrics checkoutMetrics;

    @MockitoBean
    PlatformTransactionManager transactionManager;

    @Autowired
    RedisCartRepository cartRepository;

    @Autowired
    CartService cartService;

    @Autowired
    CartAssessmentService cartAssessmentService;

    @Autowired
    CartValidationService cartValidationService;

    @Autowired
    CheckoutUlidGenerator checkoutUlidGenerator;

    @Autowired
    CheckoutCalculationService checkoutCalculationService;

    @Autowired
    CheckoutService checkoutService;

    @Test
    void springInstantiatesEveryProductionConstructorInCartAndCheckoutGraph() {
        assertThat(cartRepository).isNotNull();
        assertThat(cartService).isNotNull();
        assertThat(cartAssessmentService).isNotNull();
        assertThat(cartValidationService).isNotNull();
        assertThat(checkoutUlidGenerator).isNotNull();
        assertThat(checkoutCalculationService).isNotNull();
        assertThat(checkoutService).isNotNull();
    }

    @Configuration(proxyBeanMethods = false)
    static class ConversionConfiguration {

        @Bean
        ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }
    }
}
