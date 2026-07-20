package com.msb.ecom.payment_service.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.payment_service.config.PaymentOutboxProperties;
import com.msb.ecom.payment_service.repository.PaymentOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOutboxDispatcherTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private final PaymentOutboxRepository repository = mock(PaymentOutboxRepository.class);
    private final PaymentOutboxMessageFactory messageFactory =
            new PaymentOutboxMessageFactory(new ObjectMapper().findAndRegisterModules());
    private final PlatformTransactionManager transactionManager = new NoOpTransactionManager();

    @Test
    void disabledDispatcherDoesNotReadRowsOrCallTransport() {
        PaymentEventTransport transport = mock(PaymentEventTransport.class);
        PaymentOutboxDispatcher dispatcher = dispatcher(properties(false, false, 10), transport);

        assertThat(dispatcher.dispatchBatch()).isZero();

        verify(repository, never()).claim(anyString(), any(), any(), anyInt());
        verify(transport, never()).publish(any());
    }

    @Test
    void enabledDispatcherFailsConfigurationWhenNoTransportIsAvailable() {
        PaymentEventTransport transport = mock(PaymentEventTransport.class);
        when(transport.available()).thenReturn(false);

        assertThatThrownBy(() -> dispatcher(properties(true, false, 10), transport))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acknowledgedPublishIsMarkedOnceAfterTheTransportCall() {
        PaymentEventTransport transport = mock(PaymentEventTransport.class);
        when(transport.available()).thenReturn(true);
        PaymentOutboxRecord record = record(0);
        when(repository.claim(anyString(), eq(NOW), eq(NOW.plusSeconds(30)), eq(25)))
                .thenReturn(List.of(record));
        when(repository.markPublished(eq(record.id()), anyString(), eq(NOW))).thenReturn(1);

        assertThat(dispatcher(properties(true, false, 10), transport).dispatchBatch()).isEqualTo(1);

        verify(transport).publish(any(PaymentEventMessage.class));
        verify(repository).markPublished(eq(record.id()), anyString(), eq(NOW));
        verify(repository, never()).markFailed(
                anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyString());
    }

    @Test
    void transportFailureStoresBoundedBackoffMetadataWithoutRawExceptionText() {
        PaymentEventTransport transport = mock(PaymentEventTransport.class);
        when(transport.available()).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("secret broker detail"))
                .when(transport).publish(any());
        PaymentOutboxRecord record = record(2);
        when(repository.claim(anyString(), eq(NOW), eq(NOW.plusSeconds(30)), eq(25)))
                .thenReturn(List.of(record));

        assertThat(dispatcher(properties(true, false, 10), transport).dispatchBatch()).isZero();

        verify(repository).markFailed(
                eq(record.id()),
                anyString(),
                eq(NOW),
                eq(NOW.plusSeconds(20)),
                eq(false),
                eq("TRANSPORT_FAILURE"),
                eq("Payment event delivery will be retried."));
    }

    @Test
    void unapprovedTransportErrorCodeCollapsesToLowCardinalityFallback() {
        PaymentEventTransport transport = mock(PaymentEventTransport.class);
        when(transport.available()).thenReturn(true);
        org.mockito.Mockito.doThrow(new PaymentEventTransportException("UNIQUE_ERROR_123"))
                .when(transport).publish(any());
        PaymentOutboxRecord record = record(0);
        when(repository.claim(anyString(), eq(NOW), eq(NOW.plusSeconds(30)), eq(25)))
                .thenReturn(List.of(record));

        assertThat(dispatcher(properties(true, false, 10), transport).dispatchBatch()).isZero();

        verify(repository).markFailed(
                eq(record.id()),
                anyString(),
                eq(NOW),
                eq(NOW.plusSeconds(5)),
                eq(false),
                eq("TRANSPORT_FAILURE"),
                eq("Payment event delivery will be retried."));
    }

    @Test
    void propertiesBoundBatchAttemptsAndExponentialBackoff() {
        PaymentOutboxProperties properties = properties(true, false, 10);
        assertThat(properties.backoffAfter(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.backoffAfter(20)).isEqualTo(Duration.ofMinutes(5));
        assertThatThrownBy(() -> new PaymentOutboxProperties(
                true, false, 101, 10, Duration.ofSeconds(30),
                Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(false, true, 10))
                .isInstanceOf(IllegalStateException.class);
    }

    private PaymentOutboxDispatcher dispatcher(
            PaymentOutboxProperties properties,
            PaymentEventTransport transport) {
        return new PaymentOutboxDispatcher(
                repository,
                messageFactory,
                transport,
                properties,
                transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PaymentOutboxProperties properties(
            boolean enabled,
            boolean workerEnabled,
            int maxAttempts) {
        return new PaymentOutboxProperties(
                enabled,
                workerEnabled,
                25,
                maxAttempts,
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5));
    }

    private PaymentOutboxRecord record(int retryCount) {
        return new PaymentOutboxRecord(
                "01K00000000000000000000003",
                "payment",
                "01K00000000000000000000001",
                "payment.succeeded",
                1,
                "payment-service",
                """
                        {
                          "paymentIntentId":"01K00000000000000000000001",
                          "checkoutId":"01K00000000000000000000002",
                          "status":"SUCCEEDED",
                          "amount":"42.2500",
                          "currency":"USD",
                          "providerEventId":"fake_evt_0001"
                        }
                        """,
                "correlation-payment",
                "fake_evt_0001",
                NOW,
                NOW,
                retryCount,
                retryCount);
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
