package com.msb.ecom.order_service.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface ShippingCalculationAdapter {

    Result calculate(String currency, List<Line> lines);

    record Line(String listingId, String businessId, String storeId, BigDecimal subtotal) {
    }

    record Quote(
            String businessId,
            String storeId,
            String methodCode,
            BigDecimal amount,
            Map<String, BigDecimal> allocations
    ) {
    }

    record Result(String adapter, List<Quote> quotes) {
    }
}
