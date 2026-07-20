package com.msb.ecom.payment_service.outbox;

import com.msb.ecom.payment_service.config.PaymentOutboxProperties;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOutboxWorkerTests {

    private final PaymentOutboxDispatcher dispatcher = mock(PaymentOutboxDispatcher.class);
    private final PaymentOutboxProperties properties = mock(PaymentOutboxProperties.class);
    private final PaymentOutboxWorker worker = new PaymentOutboxWorker(dispatcher, properties);

    @Test
    void scheduledWorkerIsANoOpWhileDisabled() {
        when(properties.workerEnabled()).thenReturn(false);

        worker.dispatch();

        verify(dispatcher, never()).dispatchBatch();
    }

    @Test
    void scheduledWorkerDispatchesOneBoundedBatchWhenEnabled() {
        when(properties.workerEnabled()).thenReturn(true);

        worker.dispatch();

        verify(dispatcher).dispatchBatch();
    }
}
