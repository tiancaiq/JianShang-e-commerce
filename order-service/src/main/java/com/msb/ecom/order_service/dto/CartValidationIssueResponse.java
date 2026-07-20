package com.msb.ecom.order_service.dto;

public record CartValidationIssueResponse(
        String code,
        String message,
        String action
) {
}
