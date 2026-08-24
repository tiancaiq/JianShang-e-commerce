package com.msb.ecom.order_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class OrderDisputeContracts {
    private OrderDisputeContracts() { }

    public enum Status {
        OPEN, WAITING_FOR_BUYER, WAITING_FOR_SELLER, UNDER_ADMIN_REVIEW, READY_FOR_DECISION,
        RESOLVED_NO_ACTION, RETURN_APPROVED, REFUND_RECOMMENDED, PARTIAL_REFUND_RECOMMENDED;

        public boolean isFinal() { return ordinal() >= RESOLVED_NO_ACTION.ordinal(); }
    }
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Party { BUYER, SELLER }
    public enum Resolution {
        RESOLVED_NO_ACTION, RETURN_APPROVED, REFUND_RECOMMENDED, PARTIAL_REFUND_RECOMMENDED
    }
    public enum Assignment { UNASSIGNED, ASSIGNED_TO_ME, ASSIGNED, ALL }

    public record EvidenceReference(String referenceType, String referenceId, String label) { }
    public record CreateRequest(String businessGroupId, String reasonCode, String description,
            List<String> itemIds, List<EvidenceReference> evidence, String idempotencyKey) { }
    public record StatementRequest(String body, List<EvidenceReference> evidence, String idempotencyKey) { }
    public record VersionRequest(Long expectedVersion) { }
    public record PriorityRequest(Priority priority, Long expectedVersion, String reason) { }
    public record InformationRequest(Party party, String message, Long expectedVersion,
            String idempotencyKey) { }
    public record ReasonRequest(Long expectedVersion, String reason) { }
    public record NoteRequest(String body, String idempotencyKey) { }
    public record ResolveRequest(Resolution resolutionType, String reasonCode, String reason,
            BigDecimal recommendedRefundAmount, String returnInstructions, Instant returnDeadline,
            Long expectedVersion, String idempotencyKey) { }

    public record Summary(String disputeId, String orderId, String businessGroupId, String buyerUserId,
            String businessId, String reasonCode, Priority priority, Status status, String assignedAdminId,
            BigDecimal disputedAmount, String currency, Instant createdAt, Instant updatedAt, long version) { }
    public record Page(List<Summary> content, int page, int size, long totalElements, int totalPages,
            String sort) { }
    public record Item(String itemId, String listingId, String titleAtPurchase, String skuAtPurchase,
            String conditionAtPurchase, String imageReferenceAtPurchase, int quantity, BigDecimal unitPrice,
            BigDecimal lineTotal, String currency, CurrentListing currentListing, String adminPath) { }
    public record CurrentListing(boolean available, String title, String status, BigDecimal price,
            String currency, long version) { }
    public record Shipment(String source, String carrierDisplayName, String serviceDisplayName,
            String trackingNumber, String status, Instant shippedAt, Instant deliveredAt) { }
    public record Statement(String id, String authorType, String body, Instant createdAt) { }
    public record Evidence(String id, String authorType, String referenceType, String referenceId,
            String label, Instant createdAt) { }
    public record Note(String id, String authorAdminId, String authorDisplayName, String body,
            Instant createdAt) { }
    public record Timeline(String eventId, Instant occurredAt, String eventType, String actorType,
            String actorId, String actorDisplayName, String previousState, String newState,
            String reasonCode, String reason, String correlationId, String requestId,
            Map<String, Object> safeMetadata) { }
    public record ResolutionDetail(Resolution type, String reasonCode, String reason,
            BigDecimal recommendedRefundAmount, String currency, String returnInstructions,
            Instant returnDeadline, Instant resolvedAt) { }
    public record Capabilities(boolean canRead, boolean canClaim, boolean canRelease,
            boolean canInvestigate, boolean canAddNote, boolean canRequestBuyerInformation,
            boolean canRequestSellerInformation, boolean canChangePriority,
            boolean canMarkReadyForDecision, boolean canResolve, boolean isAssignedToMe,
            boolean isAssignedToOther, boolean isFinal, boolean isReadOnly, String readOnlyReason) { }
    public record Detail(Summary summary, String openedByType, String openedByUserId, String description,
            List<Item> disputedItems, List<Statement> statements, List<Evidence> evidence,
            List<Note> internalNotes, List<Timeline> timeline, ResolutionDetail resolution,
            BigDecimal currentlyRefundableAmount, BigDecimal alreadyRefundedAmount,
            String orderStatus, String paymentStatus, String inventoryReservationStatus,
            String inventoryReleaseStatus, String fulfillmentStatus, Shipment shipment,
            String cancellationStatus, String buyerAdminPath,
            String businessAdminPath, List<String> trustAndSafetyPaths, Capabilities capabilities) { }
}
