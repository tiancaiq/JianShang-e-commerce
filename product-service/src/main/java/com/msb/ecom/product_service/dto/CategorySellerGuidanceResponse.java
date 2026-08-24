package com.msb.ecom.product_service.dto;

public record CategorySellerGuidanceResponse(
        String id,
        String guidanceType,
        String title,
        String body,
        int displayOrder,
        String status,
        long version
) { }
