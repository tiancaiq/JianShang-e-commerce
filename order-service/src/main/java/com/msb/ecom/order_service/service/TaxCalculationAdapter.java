package com.msb.ecom.order_service.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface TaxCalculationAdapter {

    Result calculate(String currency, List<Line> lines, BuyerIdentityClient.BuyerAddress address);

    record Line(String listingId, BigDecimal subtotal, BigDecimal shipping) {
    }

    record Result(String adapter, BigDecimal amount, Map<String, BigDecimal> allocations) {
    }
}
