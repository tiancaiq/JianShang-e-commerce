package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AdminOrderContracts {
    private AdminOrderContracts() { }

    public record Page(
            List<Summary> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            String sort) {
        public Page {
            content = List.copyOf(content);
        }
    }

    public record Summary(
            String orderId,
            String buyerUserId,
            String buyerDisplayName,
            String sellerType,
            List<String> businessIds,
            List<String> businessDisplayNames,
            String status,
            String paymentStatus,
            String fulfillmentStatus,
            BigDecimal totalAmount,
            String currency,
            int itemCount,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        public Summary {
            businessIds = List.copyOf(businessIds);
            businessDisplayNames = List.copyOf(businessDisplayNames);
        }
    }

    public record Detail(
            String orderId,
            String orderNumber,
            String status,
            String paymentStatus,
            long version,
            Instant createdAt,
            Instant updatedAt,
            BuyerSummary buyerSummary,
            List<BusinessSummary> businesses,
            List<Item> orderItems,
            Pricing pricingSummary,
            ShippingAddress shippingAddressSafe,
            CheckoutSnapshot checkoutSnapshot,
            InventorySummary inventoryReservationSummary,
            PaymentSummary paymentSummary,
            List<RefundSummary> refundSummary,
            CancellationSummary cancellationSummary,
            List<RelatedEnforcement> relatedEnforcement,
            List<TimelineEntry> auditTimeline,
            Capabilities availableAdminCapabilities) {
        public Detail {
            businesses = List.copyOf(businesses);
            orderItems = List.copyOf(orderItems);
            refundSummary = List.copyOf(refundSummary);
            relatedEnforcement = List.copyOf(relatedEnforcement);
            auditTimeline = List.copyOf(auditTimeline);
        }
    }

    public record BuyerSummary(String userId, String displayName, String adminPath) { }

    public record BusinessSummary(
            String businessId,
            String legalName,
            String storeId,
            String storeNameAtPurchase,
            String currentStoreName,
            String fulfillmentStatus,
            String cancellationStatus,
            long version,
            String adminPath) { }

    public record Item(
            String listingId,
            int lineNumber,
            String titleAtPurchase,
            String skuAtPurchase,
            String conditionAtPurchase,
            String imageReferenceAtPurchase,
            long catalogVersionAtPurchase,
            String businessId,
            String storeId,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineSubtotal,
            BigDecimal shipping,
            BigDecimal taxes,
            BigDecimal discounts,
            BigDecimal lineTotal,
            String currency,
            String policyVersion,
            CurrentListing currentListing,
            String adminPath) { }

    public record CurrentListing(
            boolean available,
            String title,
            String status,
            BigDecimal price,
            String currency,
            long version,
            List<RelatedEnforcement> enforcement) {
        public CurrentListing {
            enforcement = enforcement == null ? List.of() : List.copyOf(enforcement);
        }
    }

    public record Pricing(
            BigDecimal subtotal,
            BigDecimal fees,
            BigDecimal taxes,
            BigDecimal shipping,
            BigDecimal discounts,
            BigDecimal total,
            String currency) { }

    public record ShippingAddress(
            boolean masked,
            String label,
            String recipientName,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String countryCode) { }

    public record CheckoutSnapshot(
            String checkoutId,
            String status,
            long version,
            long cartVersion,
            String cartSnapshotHash,
            Instant expiresAt,
            List<PolicySnapshot> policies) {
        public CheckoutSnapshot {
            policies = List.copyOf(policies);
        }
    }

    public record PolicySnapshot(
            String businessId,
            String version,
            String cancellationMode,
            String cancellationText,
            String shippingText,
            String returnText) { }

    public record InventorySummary(
            String reservationId,
            String status,
            boolean live,
            boolean usable,
            Long version,
            Instant expiresAt,
            List<InventoryLine> items,
            String releaseStatus,
            String availabilityNote) {
        public InventorySummary {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record InventoryLine(String listingId, int quantity) { }

    public record PaymentSummary(
            String paymentIntentId,
            String status,
            String provider,
            String providerPaymentReference,
            BigDecimal authorizedAmount,
            BigDecimal capturedAmount,
            BigDecimal refundedAmount,
            String currency,
            boolean live,
            String reconciliationState,
            String availabilityNote) { }

    public record RefundSummary(
            String source,
            String status,
            String refundId,
            String providerReference,
            BigDecimal amount,
            String currency,
            Instant completedAt) { }

    public record CancellationSummary(
            String requestId,
            String requestStatus,
            String actorType,
            String actorId,
            String reasonCode,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            Instant completedAt,
            String inventoryStatus,
            String refundStatus) { }

    public record RelatedEnforcement(
            String targetType,
            String targetId,
            String actionType,
            List<String> scopes,
            String adminPath) {
        public RelatedEnforcement {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    public record TimelineEntry(
            String eventId,
            Instant occurredAt,
            String eventType,
            String actorType,
            String actorId,
            String actorDisplayName,
            String source,
            String previousState,
            String newState,
            String reason,
            String correlationId,
            String requestId,
            Map<String, String> safeMetadata) {
        public TimelineEntry {
            safeMetadata = safeMetadata == null ? Map.of() : Map.copyOf(safeMetadata);
        }
    }

    public record Capabilities(
            boolean canRead,
            boolean canCancel,
            boolean canManage,
            boolean canViewPii,
            boolean isCancellationAllowed,
            boolean isReadOnly,
            String readOnlyReason) { }

    public record CancelRequest(
            String reasonCode,
            String reason,
            Long expectedOrderVersion,
            String idempotencyKey) { }

    public record CancellationPreview(
            String orderId,
            String currentOrderStatus,
            long currentOrderVersion,
            boolean allowed,
            String nextOrderStatus,
            String inventoryImpact,
            String paymentImpact,
            String buyerImpact,
            String sellerImpact,
            List<String> warnings,
            String blockerCode,
            String blockerMessage) {
        public CancellationPreview {
            warnings = List.copyOf(warnings);
        }
    }

    public record CancellationResult(
            String orderId,
            String cancellationRequestId,
            String status,
            long version,
            Instant requestedAt,
            boolean replayed) { }
}
