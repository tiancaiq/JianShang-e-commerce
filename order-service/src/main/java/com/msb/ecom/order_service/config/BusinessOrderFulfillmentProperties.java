package com.msb.ecom.order_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class BusinessOrderFulfillmentProperties {

    private final boolean processingEnabled;
    private final boolean manualShipmentEnabled;
    private final boolean demoDeliveryEnabled;
    private final Duration retention;
    private final int purgeBatchSize;

    public BusinessOrderFulfillmentProperties(
            @Value("${business-orders.processing-enabled:false}") boolean processingEnabled,
            @Value("${business-orders.manual-shipment-enabled:false}") boolean manualShipmentEnabled,
            @Value("${business-orders.demo-delivery-enabled:false}") boolean demoDeliveryEnabled,
            @Value("${business-orders.fulfillment-idempotency-retention:P7D}") Duration retention,
            @Value("${business-orders.fulfillment-purge-batch-size:50}") int purgeBatchSize) {
        this.processingEnabled = processingEnabled;
        this.manualShipmentEnabled = manualShipmentEnabled;
        this.demoDeliveryEnabled = demoDeliveryEnabled;
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("Fulfillment idempotency retention must be positive.");
        }
        if (purgeBatchSize < 1 || purgeBatchSize > 500) {
            throw new IllegalArgumentException("Fulfillment purge batch size must be from 1 through 500.");
        }
        this.retention = retention;
        this.purgeBatchSize = purgeBatchSize;
    }

    public boolean processingEnabled() { return processingEnabled; }
    public boolean manualShipmentEnabled() { return manualShipmentEnabled; }
    public boolean demoDeliveryEnabled() { return demoDeliveryEnabled; }
    public Duration retention() { return retention; }
    public int purgeBatchSize() { return purgeBatchSize; }
}
