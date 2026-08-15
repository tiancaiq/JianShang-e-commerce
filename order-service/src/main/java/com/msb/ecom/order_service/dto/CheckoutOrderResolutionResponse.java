package com.msb.ecom.order_service.dto;

public record CheckoutOrderResolutionResponse(String orderId, boolean confirmed) {
}
