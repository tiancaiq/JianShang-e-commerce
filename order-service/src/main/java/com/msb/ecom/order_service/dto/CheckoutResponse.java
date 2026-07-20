package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CheckoutResponse(
        String id,
        String status,
        long cartVersion,
        String currency,
        BigDecimal subtotal,
        BigDecimal shipping,
        BigDecimal tax,
        BigDecimal discount,
        BigDecimal total,
        Instant expiresAt,
        Reservation reservation,
        Address address,
        List<Item> items,
        List<ShippingQuote> shippingQuotes,
        TaxQuote taxQuote,
        List<Policy> policies,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) {
    public record Reservation(
            String id,
            String status,
            String releaseStatus
    ) {
    }

    public record Address(
            String sourceAddressId,
            long sourceVersion,
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode
    ) {
    }

    public record Item(
            String listingId,
            String businessId,
            String storeId,
            long catalogVersion,
            String title,
            String sku,
            String condition,
            String thumbnailUrl,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineSubtotal,
            BigDecimal shippingAllocation,
            BigDecimal taxAllocation,
            BigDecimal discountAllocation,
            BigDecimal lineTotal,
            String policyVersion
    ) {
    }

    public record ShippingQuote(
            String businessId,
            String storeId,
            String methodCode,
            BigDecimal amount,
            String adapter
    ) {
    }

    public record TaxQuote(
            BigDecimal amount,
            String adapter
    ) {
    }

    public record Policy(
            String businessId,
            String storeId,
            String source,
            String version,
            String shippingText,
            String cancellationText,
            String returnText
    ) {
    }
}
