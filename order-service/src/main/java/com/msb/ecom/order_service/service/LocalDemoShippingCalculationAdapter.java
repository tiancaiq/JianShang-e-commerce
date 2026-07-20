package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.config.CheckoutProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LocalDemoShippingCalculationAdapter implements ShippingCalculationAdapter {

    private final CheckoutProperties properties;

    public LocalDemoShippingCalculationAdapter(CheckoutProperties properties) {
        this.properties = properties;
    }

    @Override
    // Supplies deterministic zero shipping only for the explicitly approved local demo.
    public Result calculate(String currency, List<Line> lines) {
        Map<Group, List<Line>> groups = new LinkedHashMap<>();
        lines.forEach(line -> groups.computeIfAbsent(
                new Group(line.businessId(), line.storeId()), ignored -> new java.util.ArrayList<>()).add(line));
        List<Quote> quotes = groups.entrySet().stream()
                .map(entry -> new Quote(
                        entry.getKey().businessId(),
                        entry.getKey().storeId(),
                        "FREE_LOCAL_DEMO",
                        zero(),
                        entry.getValue().stream().collect(
                                java.util.stream.Collectors.toMap(
                                        Line::listingId,
                                        ignored -> zero(),
                                        (left, right) -> left,
                                        LinkedHashMap::new))))
                .toList();
        return new Result(properties.shippingAdapter(), quotes);
    }

    private BigDecimal zero() {
        return new BigDecimal("0.0000");
    }

    private record Group(String businessId, String storeId) {
    }
}
