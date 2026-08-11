package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.BusinessOrderReturnProperties;
import com.msb.ecom.order_service.repository.BusinessOrderReturnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessOrderReturnProcessingWorkerTests {

    @Test
    void restockDispositionCallsBothDependenciesAndCompletesOnce() {
        BusinessOrderReturnRepository repository = mock(BusinessOrderReturnRepository.class);
        ReturnInventoryClient inventory = mock(ReturnInventoryClient.class);
        ReturnPaymentClient payment = mock(ReturnPaymentClient.class);
        var record = record("RESTOCK_SELLABLE");
        when(repository.due(any(), eq(20))).thenReturn(List.of(record));
        when(repository.claimProcessing(record.id())).thenReturn(true);
        when(repository.lockBusinessGroup(record.businessId(), record.businessOrderId()))
                .thenReturn(Optional.of(group()));
        when(payment.refund(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new ReturnPaymentClient.Result(id(10), new BigDecimal("20.0000")));

        worker(repository, inventory, payment).process();

        verify(inventory).restock(record.reservationId(), record.orderId(), record.id(),
                record.businessId(), "return-restock:" + record.id());
        verify(payment).refund(record.paymentIntentId(), record.orderId(), record.businessOrderId(),
                record.id(), new BigDecimal("20.0000"), "USD", "return-refund:" + record.id());
        verify(repository).complete(eq(record.id()), eq(id(10)), eq(new BigDecimal("20.0000")),
                eq(record.id()), any(), anyString(), anyString(), anyString(), contains("RETURN_COMPLETED"));
        verify(repository, never()).retry(anyString(), any(), anyString());
    }

    @Test
    void doNotRestockSkipsInventoryAndDependencyFailureReturnsWorkToRetryQueue() {
        BusinessOrderReturnRepository repository = mock(BusinessOrderReturnRepository.class);
        ReturnInventoryClient inventory = mock(ReturnInventoryClient.class);
        ReturnPaymentClient payment = mock(ReturnPaymentClient.class);
        var record = record("DO_NOT_RESTOCK");
        when(repository.due(any(), eq(20))).thenReturn(List.of(record));
        when(repository.claimProcessing(record.id())).thenReturn(true);
        when(repository.lockBusinessGroup(record.businessId(), record.businessOrderId()))
                .thenReturn(Optional.of(group()));
        when(payment.refund(anyString(), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("temporary payment outage"));

        worker(repository, inventory, payment).process();

        verifyNoInteractions(inventory);
        verify(repository).retry(eq(record.id()), any(), eq("RETURN_DEPENDENCY_UNAVAILABLE"));
        verify(repository, never()).complete(anyString(), anyString(), any(), anyString(), any(),
                anyString(), anyString(), anyString(), anyString());
    }

    private BusinessOrderReturnProcessingWorker worker(BusinessOrderReturnRepository repository,
            ReturnInventoryClient inventory, ReturnPaymentClient payment) {
        return new BusinessOrderReturnProcessingWorker(
                new BusinessOrderReturnProperties(true, true, Duration.ofDays(30), Duration.ofSeconds(2), 20),
                repository, inventory, payment, new CheckoutUlidGenerator(Clock.systemUTC()),
                new ObjectMapper().findAndRegisterModules(), new TestTransactionManager());
    }

    private BusinessOrderReturnRepository.ReturnRecord record(String disposition) {
        return new BusinessOrderReturnRepository.ReturnRecord(id(1), id(2), id(3), id(4), id(5),
                "Demo Store", "DAMAGED", null, "LOCAL_DEMO_RETURN_POLICY_V1",
                Instant.now(), Instant.now().plus(Duration.ofDays(30)), "RETURN_RECEIVED", "PENDING",
                disposition, Instant.now(), null, null, "USD", null, 3, id(6), id(7),
                null, null, null, null);
    }

    private BusinessOrderReturnRepository.Group group() {
        return new BusinessOrderReturnRepository.Group(id(4), id(3), id(5), "Demo Store",
                "DELIVERED", "NONE", 4, new BigDecimal("20.0000"), "USD", id(6), id(7), Instant.now());
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
