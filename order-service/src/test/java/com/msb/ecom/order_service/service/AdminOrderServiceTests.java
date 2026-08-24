package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProcessingProperties;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.repository.AdminOrderRepository;
import com.msb.ecom.order_service.repository.AdminOrderRepository.DetailRow;
import com.msb.ecom.order_service.repository.AdminOrderRepository.GroupRow;
import com.msb.ecom.order_service.repository.AdminOrderRepository.PolicyRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdminOrderServiceTests {
    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");

    @Test
    void permitsOnlyPaidPreAcceptanceOrdersInsideTheSnapshotPolicyWindow() {
        AdminOrderService service = service(true, true);

        AdminOrderService.Evaluation result = service.evaluate(order("CONFIRMED", "SUCCEEDED"),
                List.of(group("PENDING_ACCEPTANCE", "NONE", NOW.plusSeconds(60))),
                List.of(policy("BEFORE_FULFILLMENT")), NOW);

        assertThat(result.allowed()).isTrue();
        assertThat(result.blockerCode()).isNull();
    }

    @Test
    void blocksCancellationAfterFulfillmentStarts() {
        AdminOrderService.Evaluation result = service(true, true).evaluate(order("CONFIRMED", "SUCCEEDED"),
                List.of(group("PROCESSING", "NONE", NOW.plusSeconds(60))),
                List.of(policy("BEFORE_FULFILLMENT")), NOW);

        assertThat(result.allowed()).isFalse();
        assertThat(result.blockerCode()).isEqualTo("ORDER_ADMIN_ACTION_NOT_ALLOWED");
    }

    @Test
    void blocksPaidOrderWhenCompensationWorkersAreDisabled() {
        AdminOrderService.Evaluation result = service(true, false).evaluate(order("CONFIRMED", "SUCCEEDED"),
                List.of(group("PENDING_ACCEPTANCE", "NONE", NOW.plusSeconds(60))),
                List.of(policy("BEFORE_FULFILLMENT")), NOW);

        assertThat(result.allowed()).isFalse();
        assertThat(result.blockerCode()).isEqualTo("ORDER_FINANCIAL_STATE_UNSUPPORTED");
    }

    private AdminOrderService service(boolean requestsEnabled, boolean processingEnabled) {
        return new AdminOrderService(mock(CurrentActorProvider.class), mock(AdminOrderAuthorizationClient.class),
                mock(AdminOrderRepository.class), mock(AdminOrderListingContextClient.class),
                mock(PaymentIntentClient.class), mock(InventoryReservationClient.class),
                new OrderCancellationProperties(requestsEnabled, Duration.ofDays(7), 100),
                new OrderCancellationProcessingProperties(processingEnabled, 25, Duration.ofSeconds(2)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private DetailRow order(String status, String paymentStatus) {
        return new DetailRow(id(1), id(2), id(3), id(4), id(5), status, paymentStatus, "USD",
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.00"), 4, NOW, NOW, "COMPLETED", 1, 1, "hash",
                NOW.plusSeconds(60), id(6), "COMMITTED", 1L, "NOT_REQUIRED");
    }

    private GroupRow group(String fulfillment, String cancellation, Instant cutoff) {
        return new GroupRow(id(7), id(8), id(9), "Store", fulfillment, cancellation, 0, cutoff);
    }

    private PolicyRow policy(String mode) {
        return new PolicyRow(id(8), "LOCAL_DEMO_CANCELLATION_V1", mode, "Cancel", "Ship", "Return");
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }
}
