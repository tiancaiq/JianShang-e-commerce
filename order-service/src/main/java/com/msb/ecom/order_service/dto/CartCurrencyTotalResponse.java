package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;

public record CartCurrencyTotalResponse(
        String currency,
        BigDecimal amount
) {
}
