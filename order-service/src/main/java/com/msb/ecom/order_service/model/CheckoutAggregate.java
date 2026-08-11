package com.msb.ecom.order_service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CheckoutAggregate(
        String id,
        String buyerId,
        CheckoutStatus status,
        long version,
        long cartVersion,
        String cartSnapshotHash,
        String currency,
        BigDecimal subtotal,
        BigDecimal shipping,
        BigDecimal tax,
        BigDecimal discount,
        BigDecimal total,
        Instant expiresAt,
        String reservationId,
        String reservationStatus,
        Long reservationVersion,
        CheckoutReleaseStatus releaseStatus,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Address address,
        List<Item> items,
        List<ShippingQuote> shippingQuotes,
        TaxQuote taxQuote,
        List<Policy> policies
) {
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
            String id,
            int lineNumber,
            String listingId,
            String businessId,
            String storeId,
            String storeName,
            long catalogVersion,
            String title,
            String sku,
            String condition,
            String thumbnailUrl,
            int quantity,
            BigDecimal unitPrice,
            String currency,
            BigDecimal lineSubtotal,
            BigDecimal shippingAllocation,
            BigDecimal taxAllocation,
            BigDecimal discountAllocation,
            BigDecimal lineTotal,
            String policySnapshotId,
            String policyVersion
    ) {
        public Item(
                String id,
                int lineNumber,
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
                String currency,
                BigDecimal lineSubtotal,
                BigDecimal shippingAllocation,
                BigDecimal taxAllocation,
                BigDecimal discountAllocation,
                BigDecimal lineTotal,
                String policySnapshotId,
                String policyVersion) {
            this(id, lineNumber, listingId, businessId, storeId, null, catalogVersion, title, sku,
                    condition, thumbnailUrl, quantity, unitPrice, currency, lineSubtotal,
                    shippingAllocation, taxAllocation, discountAllocation, lineTotal,
                    policySnapshotId, policyVersion);
        }
    }

    public record Policy(
            String id,
            String businessId,
            String storeId,
            String sourcePolicyId,
            String source,
            String version,
            String shippingText,
            String cancellationText,
            String returnText
    ) {
    }

    public record ShippingQuote(
            String id,
            String businessId,
            String storeId,
            String methodCode,
            BigDecimal amount,
            String currency,
            String adapter,
            Instant quotedAt
    ) {
    }

    public record TaxQuote(
            String id,
            BigDecimal amount,
            String currency,
            String adapter,
            Instant quotedAt
    ) {
    }
}
