package com.msb.ecom.product_service.dto;

public record CategoryAttributeOptionResponse(
        String id,
        String value,
        String label,
        int displayOrder,
        String status,
        long version
) { }
