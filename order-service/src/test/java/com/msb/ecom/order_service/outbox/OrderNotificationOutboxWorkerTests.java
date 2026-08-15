package com.msb.ecom.order_service.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderNotificationOutboxWorkerTests {
    private final OrderNotificationOutboxRepository repository = mock(OrderNotificationOutboxRepository.class);
    private final NotificationEventTransport transport = mock(NotificationEventTransport.class);
    private final OrderNotificationOutboxWorker worker = new OrderNotificationOutboxWorker(
            true, true, 25, Duration.ofSeconds(2), repository, transport);

    @Test
    void confirmationCreatesOneBuyerAndOneNotificationPerBusiness() {
        var event = event("order.confirmed", "ORDER", id(1), 2);
        when(repository.audience(event)).thenReturn(audience());

        CommerceNotificationEvent projected = worker.project(event);

        assertThat(projected.targets()).extracting(CommerceNotificationEvent.Target::notificationType)
                .containsExactly("BUYER_ORDER_CONFIRMED", "SELLER_NEW_ORDER", "SELLER_NEW_ORDER");
        assertThat(projected.targets()).extracting(CommerceNotificationEvent.Target::scopeId)
                .contains(id(9), business(1), business(2));
    }

    @Test
    void cancellationCreatesIndependentBuyerRefundAndSellerNotifications() {
        var event = event("order.cancellation_completed", "ORDER", id(1), 1);
        when(repository.audience(event)).thenReturn(audience());

        CommerceNotificationEvent projected = worker.project(event);

        assertThat(projected.targets()).extracting(CommerceNotificationEvent.Target::notificationType)
                .containsExactly("BUYER_ORDER_CANCELLED", "BUYER_REFUND_COMPLETED",
                        "SELLER_ORDER_CANCELLED", "SELLER_ORDER_CANCELLED");
    }

    @Test
    void fulfillmentEventsMapToTheBoundedBuyerTypesAndRelevantBusinessGroup() {
        assertBuyerProjection("business_order.accepted", "BUYER_ORDER_ACCEPTED");
        assertBuyerProjection("business_order.processing_started", "BUYER_ORDER_PROCESSING");
        assertBuyerProjection("business_order.shipped", "BUYER_ORDER_SHIPPED");
        assertBuyerProjection("business_order.delivered_demo", "BUYER_ORDER_DELIVERED");
    }

    @Test
    void returnEventsTargetOnlyTheReturnedBusinessAndOwningBuyer() {
        assertReturnProjection("return.requested", "SELLER_RETURN_REQUESTED", business(1));
        assertReturnProjection("return.authorized", "BUYER_RETURN_AUTHORIZED", id(9));
        assertReturnProjection("return.received", "BUYER_RETURN_RECEIVED", id(9));
        assertReturnProjection("return.refund_completed", "BUYER_RETURN_REFUND_COMPLETED", id(9));
    }

    @Test
    void notificationOutageSchedulesRetryWithoutChangingCommerceState() {
        var event = event("business_order.shipped", "BUSINESS_ORDER", id(2), 1);
        when(repository.due(any(), eq(25))).thenReturn(List.of(event));
        when(repository.audience(event)).thenReturn(audience());
        doThrow(new RuntimeException("offline")).when(transport).send(any());

        worker.dispatch();

        verify(repository).failed(eq(event.id()), any(), eq("NOTIFICATION_DELIVERY_UNAVAILABLE"));
        verify(repository, never()).published(any(), any());
    }

    private void assertBuyerProjection(String eventType, String notificationType) {
        var event = event(eventType, "BUSINESS_ORDER", id(2), 1);
        when(repository.audience(event)).thenReturn(audience());

        CommerceNotificationEvent projected = worker.project(event);

        assertThat(projected.targets()).singleElement().satisfies(target -> {
            assertThat(target.notificationType()).isEqualTo(notificationType);
            assertThat(target.scopeId()).isEqualTo(id(9));
            assertThat(target.businessId()).isEqualTo(business(1));
            assertThat(target.businessOrderId()).isEqualTo(id(2));
        });
    }

    private void assertReturnProjection(String eventType, String notificationType, String scopeId) {
        var event = event(eventType, "RETURN", id(6), 1);
        when(repository.audience(event)).thenReturn(audience());
        when(repository.returnBusinessOrderId(id(6))).thenReturn(id(2));

        CommerceNotificationEvent projected = worker.project(event);

        assertThat(projected.targets()).singleElement().satisfies(target -> {
            assertThat(target.notificationType()).isEqualTo(notificationType);
            assertThat(target.scopeId()).isEqualTo(scopeId);
            assertThat(target.businessId()).isEqualTo(business(1));
            assertThat(target.businessOrderId()).isEqualTo(id(2));
        });
    }

    private OrderNotificationOutboxRepository.OrderAudience audience() {
        return new OrderNotificationOutboxRepository.OrderAudience(id(1), id(9), List.of(
                new OrderNotificationOutboxRepository.BusinessAudience(id(2), business(1), "Harbor Cart Supply"),
                new OrderNotificationOutboxRepository.BusinessAudience(id(3), business(2), "Maple Goods")));
    }

    private OrderNotificationOutboxRepository.OutboxRecord event(
            String type, String aggregateType, String aggregateId, int version) {
        return new OrderNotificationOutboxRepository.OutboxRecord(
                id(8), aggregateType, aggregateId, type, version, "correlation", id(7), Instant.now());
    }

    private static String id(int suffix) { return "01KAAAAAAAAAAAAAAAAAAAAAA" + suffix; }
    private static String business(int suffix) { return "01JBBBBBBBBBBBBBBBBBBBBBB" + suffix; }
}
