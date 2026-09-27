package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderCancellationProcessingProperties;
import com.msb.ecom.order_service.repository.OrderCancellationProcessingRepository;
import com.msb.ecom.order_service.repository.OrderCancellationProcessingRepository.CompensationRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderCancellationProcessingServiceTests {

    private static final String COMPENSATION_ID = id(1);
    private static final String REQUEST_ID = id(2);
    private static final String ORDER_ID = id(3);
    private static final String RESERVATION_ID = id(4);
    private static final String PAYMENT_INTENT_ID = id(5);

    private final OrderCancellationProcessingRepository repository =
            mock(OrderCancellationProcessingRepository.class);
    private final CancellationInventoryClient inventory =
            mock(CancellationInventoryClient.class);
    private final CancellationPaymentClient payments =
            mock(CancellationPaymentClient.class);

    @Test
    void refundFailurePreservesInventoryProgressThenRetriesWithTheSameActionKey() {
        CompensationRecord pending = record("PENDING", "PENDING", null, null, 0);
        CompensationRecord inventoryDone = record(
                "SUCCEEDED", "PENDING", null, null, 1);
        CompensationRecord completed = record(
                "SUCCEEDED", "SUCCEEDED", id(6), "fake-refund-reference", 1);

        when(repository.dueCompensations(any(), eq(25)))
                .thenReturn(List.of(pending), List.of(inventoryDone));
        when(repository.findCompensation(COMPENSATION_ID)).thenReturn(
                Optional.of(pending),
                Optional.of(inventoryDone),
                Optional.of(inventoryDone),
                Optional.of(inventoryDone),
                Optional.of(completed));
        when(repository.complete(eq(COMPENSATION_ID), any())).thenReturn(1);
        when(repository.completeRequest(eq(REQUEST_ID), any())).thenReturn(1);
        when(payments.refund(
                PAYMENT_INTENT_ID,
                ORDER_ID,
                REQUEST_ID,
                "cancel-refund:" + REQUEST_ID))
                .thenThrow(new IllegalStateException("controlled provider failure"))
                .thenReturn(new CancellationPaymentClient.RefundResult(
                        id(6), "fake-refund-reference"));

        OrderCancellationProcessingService service = service();
        service.processCompensations();

        verify(repository).inventorySucceeded(eq(COMPENSATION_ID), any());
        verify(repository, never()).refundSucceeded(
                eq(COMPENSATION_ID), any(), any(), any());
        verify(repository, never()).complete(eq(COMPENSATION_ID), any());
        ArgumentCaptor<Instant> nextAttempt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> failedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).retryLater(
                eq(COMPENSATION_ID),
                eq("DEPENDENCY_UNAVAILABLE"),
                nextAttempt.capture(),
                failedAt.capture());
        assertThat(nextAttempt.getValue()).isAfter(failedAt.getValue());

        service.processCompensations();

        verify(inventory).restock(
                RESERVATION_ID,
                ORDER_ID,
                REQUEST_ID,
                "cancel-inventory:" + REQUEST_ID);
        verify(payments, times(2)).refund(
                PAYMENT_INTENT_ID,
                ORDER_ID,
                REQUEST_ID,
                "cancel-refund:" + REQUEST_ID);
        verify(repository).refundSucceeded(
                eq(COMPENSATION_ID), eq(id(6)), eq("fake-refund-reference"), any());
        verify(repository).complete(eq(COMPENSATION_ID), any());
        verify(repository).completeRequest(eq(REQUEST_ID), any());
    }

    @Test
    void inventoryFailureDefersRefundUntilRestockRecoversWithoutChangingKeys() {
        CompensationRecord pending = record("PENDING", "PENDING", null, null, 0);
        CompensationRecord retry = record("PENDING", "PENDING", null, null, 1);
        CompensationRecord inventoryDone = record(
                "SUCCEEDED", "PENDING", null, null, 1);
        CompensationRecord completed = record(
                "SUCCEEDED", "SUCCEEDED", id(7), "fake-refund-reference", 1);

        when(repository.dueCompensations(any(), eq(25)))
                .thenReturn(List.of(pending), List.of(retry));
        when(repository.findCompensation(COMPENSATION_ID)).thenReturn(
                Optional.of(pending),
                Optional.of(retry),
                Optional.of(inventoryDone),
                Optional.of(completed));
        when(repository.complete(eq(COMPENSATION_ID), any())).thenReturn(1);
        when(repository.completeRequest(eq(REQUEST_ID), any())).thenReturn(1);
        doThrow(new IllegalStateException("controlled inventory failure"))
                .doNothing()
                .when(inventory)
                .restock(
                        RESERVATION_ID,
                        ORDER_ID,
                        REQUEST_ID,
                        "cancel-inventory:" + REQUEST_ID);
        when(payments.refund(
                PAYMENT_INTENT_ID,
                ORDER_ID,
                REQUEST_ID,
                "cancel-refund:" + REQUEST_ID))
                .thenReturn(new CancellationPaymentClient.RefundResult(
                        id(7), "fake-refund-reference"));

        OrderCancellationProcessingService service = service();
        service.processCompensations();

        verify(payments, never()).refund(any(), any(), any(), any());
        verify(repository).retryLater(
                eq(COMPENSATION_ID), eq("DEPENDENCY_UNAVAILABLE"), any(), any());

        service.processCompensations();

        verify(inventory, times(2)).restock(
                RESERVATION_ID,
                ORDER_ID,
                REQUEST_ID,
                "cancel-inventory:" + REQUEST_ID);
        verify(payments).refund(
                PAYMENT_INTENT_ID,
                ORDER_ID,
                REQUEST_ID,
                "cancel-refund:" + REQUEST_ID);
        verify(repository).complete(eq(COMPENSATION_ID), any());
        verify(repository).completeRequest(eq(REQUEST_ID), any());
    }

    private OrderCancellationProcessingService service() {
        return new OrderCancellationProcessingService(
                new OrderCancellationProcessingProperties(
                        true, 25, Duration.ofMillis(25)),
                repository,
                inventory,
                payments,
                new CheckoutUlidGenerator(),
                new ObjectMapper().findAndRegisterModules(),
                new NoOpTransactionManager());
    }

    private static CompensationRecord record(
            String inventoryStatus,
            String refundStatus,
            String refundId,
            String refundReference,
            int retryCount) {
        return new CompensationRecord(
                COMPENSATION_ID,
                REQUEST_ID,
                ORDER_ID,
                RESERVATION_ID,
                PAYMENT_INTENT_ID,
                new BigDecimal("18.2500"),
                "USD",
                inventoryStatus,
                refundStatus,
                refundId,
                refundReference,
                retryCount);
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }

    private static final class NoOpTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
