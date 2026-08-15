package com.msb.ecom.order_service.service;

public interface CancellationPaymentClient {
    RefundResult refund(String paymentIntentId, String orderId, String cancellationRequestId, String key);

    record RefundResult(String refundId, String providerReference, String status) {
        public RefundResult(String refundId, String providerReference) {
            this(refundId, providerReference, "SUCCEEDED");
        }
    }
}
