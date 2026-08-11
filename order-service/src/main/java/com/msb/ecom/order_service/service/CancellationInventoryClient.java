package com.msb.ecom.order_service.service;

public interface CancellationInventoryClient {
    void restock(String reservationId, String orderId, String cancellationRequestId, String key);
}
