package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.PurchasedCartReconciliation;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.repository.CartRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchasedCartReconciliationServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    @Test
    void springSelectsTheProductionConstructor() {
        CheckoutRepository checkouts = mock(CheckoutRepository.class);
        CartRepository carts = mock(CartRepository.class);
        CheckoutProperties properties = mock(CheckoutProperties.class);
        when(properties.enabled()).thenReturn(true);

        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(CheckoutRepository.class, () -> checkouts);
            context.registerBean(CartRepository.class, () -> carts);
            context.registerBean(CheckoutProperties.class, () -> properties);
            context.register(PurchasedCartReconciliationService.class);
            context.refresh();

            context.getBean(PurchasedCartReconciliationService.class);
        }
    }

    @Test
    void completesDurableWorkOnlyAfterRedisReconciliationSucceeds() {
        CheckoutRepository checkouts = mock(CheckoutRepository.class);
        CartRepository carts = mock(CartRepository.class);
        PurchasedCartReconciliation work = work();
        var service = new PurchasedCartReconciliationService(
                checkouts, carts, Clock.fixed(NOW, ZoneOffset.UTC));

        service.reconcile(work);

        verify(carts).reconcilePurchased(work);
        verify(checkouts).markCartReconciled(work.checkoutId(), NOW);
    }

    @Test
    void redisFailureSchedulesRetryWithoutCompletingWork() {
        CheckoutRepository checkouts = mock(CheckoutRepository.class);
        CartRepository carts = mock(CartRepository.class);
        PurchasedCartReconciliation work = work();
        doThrow(new IllegalStateException("redis unavailable"))
                .when(carts).reconcilePurchased(work);
        var service = new PurchasedCartReconciliationService(
                checkouts, carts, Clock.fixed(NOW, ZoneOffset.UTC));

        service.reconcile(work);

        verify(checkouts).markCartReconciliationRetry(
                eq(work.checkoutId()),
                eq("CART_RECONCILIATION_RETRY_REQUIRED"),
                eq(NOW.plusSeconds(5)),
                eq(NOW));
    }

    private PurchasedCartReconciliation work() {
        return new PurchasedCartReconciliation(
                "01C00000000000000000000001",
                "keycloak-subject-1",
                4,
                List.of(new PurchasedCartReconciliation.Line(
                        "01L00000000000000000000001",
                        NOW.minusSeconds(30))));
    }
}
