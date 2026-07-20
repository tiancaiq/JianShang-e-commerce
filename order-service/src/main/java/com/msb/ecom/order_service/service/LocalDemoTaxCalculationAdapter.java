package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.config.CheckoutProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class LocalDemoTaxCalculationAdapter implements TaxCalculationAdapter {

    private final CheckoutProperties properties;

    public LocalDemoTaxCalculationAdapter(CheckoutProperties properties) {
        this.properties = properties;
    }

    @Override
    // Supplies deterministic zero tax without claiming exemption or provider calculation.
    public Result calculate(
            String currency,
            List<Line> lines,
            BuyerIdentityClient.BuyerAddress address) {
        var allocations = new LinkedHashMap<String, BigDecimal>();
        lines.forEach(line -> allocations.put(line.listingId(), new BigDecimal("0.0000")));
        return new Result(properties.taxAdapter(), new BigDecimal("0.0000"), allocations);
    }
}
