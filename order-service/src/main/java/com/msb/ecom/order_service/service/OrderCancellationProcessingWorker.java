package com.msb.ecom.order_service.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderCancellationProcessingWorker {

    private final OrderCancellationProcessingService service;

    public OrderCancellationProcessingWorker(OrderCancellationProcessingService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${order.cancellation-processing.interval-ms:500}")
    public void run() {
        if (!service.enabled()) {
            return;
        }
        service.decidePending();
        service.processCompensations();
    }
}
