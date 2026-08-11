package com.msb.ecom.notification_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.notification_service.model.CommerceNotificationEvent;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CommerceNotificationConsumerTests {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationUlidGenerator ids = mock(NotificationUlidGenerator.class);

    @Test
    void oneEventPersistsDistinctUserAndBusinessRecipientsAtomically() {
        when(repository.lockSource(anyString(), anyString())).thenReturn(Optional.empty());
        when(ids.next()).thenReturn(id(4), id(5), id(6));
        var consumer = consumer(true);

        NotificationConsumeResult result = consumer.consume(new CommerceNotificationEvent(
                id(1), "order.confirmed", 2, Instant.parse("2026-08-10T12:00:00Z"),
                "order-service", "ORDER", id(2), "correlation", id(3), List.of(
                    target("USER", id(7), "BUYER_ORDER_CONFIRMED", null, null),
                    target("BUSINESS", business(1), "SELLER_NEW_ORDER", business(1), id(8)),
                    target("BUSINESS", business(2), "SELLER_NEW_ORDER", business(2), id(9)))));

        assertThat(result.outcome()).isEqualTo(NotificationConsumeResult.Outcome.CREATED);
        verify(repository, times(3)).insertCommerceNotification(
                anyString(), anyString(), any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any(), any());
        verify(repository).complete(anyString(), eq(id(1)), eq("CREATED"), isNull(), any());
    }

    @Test
    void disabledConsumerDoesNoPersistenceWork() {
        NotificationConsumeResult result = consumer(false).consume(null);
        assertThat(result.outcome()).isEqualTo(NotificationConsumeResult.Outcome.DISABLED);
        verifyNoInteractions(repository, ids);
    }

    @Test
    void mismatchedEventAndNotificationTypeIsPoison() {
        var input = new CommerceNotificationEvent(
                id(1), "business_order.shipped", 1, Instant.now(), "order-service",
                "BUSINESS_ORDER", id(2), "correlation", id(3),
                List.of(target("USER", id(7), "BUYER_REFUND_COMPLETED", null, null)));
        assertThat(consumer(true).consume(input).outcome())
                .isEqualTo(NotificationConsumeResult.Outcome.POISON);
        verifyNoInteractions(repository, ids);
    }

    @Test
    void transientPersistenceFailureRequestsSafeReplay() {
        when(repository.lockSource(anyString(), anyString()))
                .thenThrow(new TransientDataAccessResourceException("temporary database outage"));

        NotificationConsumeResult result = consumer(true).consume(new CommerceNotificationEvent(
                id(1), "order.confirmed", 2, Instant.parse("2026-08-10T12:00:00Z"),
                "order-service", "ORDER", id(2), "correlation", id(3),
                List.of(target("USER", id(7), "BUYER_ORDER_CONFIRMED", null, null))));

        assertThat(result.outcome()).isEqualTo(NotificationConsumeResult.Outcome.RETRY_REQUIRED);
        assertThat(result.safeCode()).isEqualTo("NOTIFICATION_PERSISTENCE_RETRY_REQUIRED");
        verify(repository, never()).complete(anyString(), anyString(), anyString(), any(), any());
    }

    private CommerceNotificationConsumer consumer(boolean enabled) {
        return new CommerceNotificationConsumer(enabled, "notification-service-commerce-v1",
                Duration.ofDays(180), new ObjectMapper().findAndRegisterModules(),
                repository, ids, new TestTransactionManager());
    }

    private CommerceNotificationEvent.Target target(
            String scope, String scopeId, String type, String businessId, String businessOrderId) {
        return new CommerceNotificationEvent.Target(scope, scopeId, type, id(2),
                businessId, businessOrderId, businessOrderId == null ? null : "Store");
    }

    private static String id(int suffix) { return "01KAAAAAAAAAAAAAAAAAAAAAA" + suffix; }
    private static String business(int suffix) { return "01JBBBBBBBBBBBBBBBBBBBBBB" + suffix; }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
