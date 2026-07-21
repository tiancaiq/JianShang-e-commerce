package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderConfirmationProperties;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.OrderConfirmationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentSucceededOrderConfirmationHandlerTests {

    @Test
    void defaultOffFailsBeforeRepositoryOrInventoryAccess() {
        CheckoutRepository checkouts = mock(CheckoutRepository.class);
        CheckoutPaymentBindingRepository bindings = mock(CheckoutPaymentBindingRepository.class);
        OrderConfirmationRepository orders = mock(OrderConfirmationRepository.class);
        InventoryReservationClient inventory = mock(InventoryReservationClient.class);
        PaymentSucceededOrderConfirmationHandler handler =
                new PaymentSucceededOrderConfirmationHandler(
                        new OrderConfirmationProperties(
                                false,
                                "order-service-order-confirmation-v1",
                                Duration.ofSeconds(30)),
                        new PaymentEventValidator(),
                        checkouts,
                        bindings,
                        orders,
                        inventory,
                        mock(CheckoutUlidGenerator.class),
                        new ObjectMapper().findAndRegisterModules(),
                        mock(PlatformTransactionManager.class));

        assertThatThrownBy(() -> handler.handle(event()))
                .isInstanceOfSatisfying(OrderConfirmationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("ORDER_CONFIRMATION_DISABLED"));

        verify(checkouts, never()).lock(org.mockito.ArgumentMatchers.any());
        verify(bindings, never()).lock(org.mockito.ArgumentMatchers.any());
        verify(inventory, never()).commit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void logReferenceUsesStableHashWithoutRawIdentifier() {
        String rawId = "01K00000000000000000000900";

        String reference = PaymentSucceededOrderConfirmationHandler.logReference(rawId);

        assertThat(reference)
                .hasSize(12)
                .doesNotContain(rawId);
        assertThat(PaymentSucceededOrderConfirmationHandler.logReference(rawId))
                .isEqualTo(reference);
        assertThat(PaymentSucceededOrderConfirmationHandler.logReference(null))
                .isEqualTo("unavailable");
    }

    private PaymentEventEnvelope event() {
        return new PaymentEventEnvelope(
                "01K00000000000000000000900",
                "payment.succeeded",
                1,
                Instant.parse("2026-07-20T01:00:00Z"),
                "correlation-order-confirmation",
                "01K00000000000000000000800",
                "01K00000000000000000000001",
                "01K00000000000000000000800",
                new PaymentEventEnvelope.Payload(
                        "SUCCEEDED",
                        new BigDecimal("30.0000"),
                        "USD",
                        "fake_evt_order_0001"));
    }
}
