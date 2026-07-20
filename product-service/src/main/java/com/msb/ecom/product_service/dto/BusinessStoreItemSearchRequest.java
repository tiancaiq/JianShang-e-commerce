package com.msb.ecom.product_service.dto;

public record BusinessStoreItemSearchRequest(
        String q,
        String status,
        String cursor,
        Integer limit) {
}
