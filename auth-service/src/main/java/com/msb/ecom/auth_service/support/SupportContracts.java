package com.msb.ecom.auth_service.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SupportContracts {
    private SupportContracts() { }

    public enum Category { ACCOUNT_HELP, ORDER_HELP, PAYMENT_HELP, REFUND_HELP, SELLER_HELP,
        BUSINESS_VERIFICATION, LISTING_HELP, TECHNICAL_ISSUE, OTHER }
    public enum Status { OPEN, ASSIGNED, UNDER_REVIEW, WAITING_FOR_USER, RESOLVED }
    public enum Priority { LOW, MEDIUM, HIGH, URGENT }
    public enum Assignment { UNASSIGNED, ASSIGNED_TO_ME, ASSIGNED, ALL }
    public enum TargetType { USER, ORDER, BUSINESS, LISTING, PAYMENT, REFUND, DISPUTE,
        TRUST_AND_SAFETY_REPORT, INVESTIGATION_CASE }
    public enum RelationType { SUBMITTED_WITH, RELATED, ESCALATION }
    public enum DestinationType { DISPUTE, TRUST_AND_SAFETY, FINANCE, ORDER_OPERATIONS }
    public enum ResolutionCode { INFORMATION_PROVIDED, ISSUE_RESOLVED, USER_GUIDANCE_PROVIDED,
        DUPLICATE, ESCALATED_TO_SPECIALIZED_WORKFLOW, NO_ACTION_REQUIRED, OTHER }

    public record CreateTicketRequest(Category category, String subject, String description,
                                      String linkedOrderId, String linkedBusinessId, String linkedListingId) { }
    public record MessageRequest(String body, Long expectedVersion, String idempotencyKey) { }
    public record VersionRequest(Long expectedVersion) { }
    public record PriorityRequest(Priority priority, Long expectedVersion, String reason) { }
    public record NoteRequest(String body, Long expectedVersion, String idempotencyKey) { }
    public record LinkRequest(TargetType targetType, String targetId, RelationType relationType,
                              Long expectedVersion) { }
    public record UnlinkRequest(Long expectedVersion, String reason) { }
    public record EscalationRequest(DestinationType destinationType, String destinationId,
                                    String reason, Long expectedVersion, String idempotencyKey) { }
    public record ResolutionRequest(ResolutionCode resolutionCode, String reason,
                                    Long expectedVersion, String idempotencyKey) { }

    public record UserPage(List<UserSummary> items, int page, int size, long totalElements, int totalPages) { }
    public record UserSummary(String ticketId, Category category, String subject, Status status,
                              Instant createdAt, Instant updatedAt) { }
    public record UserMessage(String messageId, String author, String body, Instant createdAt) { }
    public record UserLink(TargetType targetType, String targetId, String safeLabel) { }
    public record UserEvent(String eventId, Instant occurredAt, String eventType, String safeLabel) { }
    public record UserDetail(String ticketId, Category category, String subject, String description,
                             Status status, Instant createdAt, Instant updatedAt, long version,
                             List<UserLink> linkedReferences, List<UserMessage> messages,
                             String resolutionCode, String resolutionReason, Instant resolvedAt,
                             List<UserEvent> history) { }

    public record AdminPage(List<AdminSummary> items, int page, int size, long totalElements,
                            int totalPages, String sort) { }
    public record AdminSummary(String ticketId, String requesterUserId, String safeRequesterLabel,
                               Category category, String subject, Priority priority, Status status,
                               String assignedAdminId, String linkedOrderId, Instant createdAt,
                               Instant updatedAt, long version) { }
    public record RequesterSummary(String userId, String safeDisplayName, String accountState,
                                   boolean piiAvailable, String email) { }
    public record AdminMessage(String messageId, String authorType, String authorUserId,
                               String authorDisplayName, String body, Instant createdAt) { }
    public record InternalNote(String noteId, String authorAdminId, String authorDisplayName,
                               String body, Instant createdAt) { }
    public record AdminLink(TargetType targetType, String targetId, RelationType relationType,
                            String safeLabel, String adminPath, String linkedByType,
                            String linkedBy, Instant linkedAt) { }
    public record Escalation(String escalationId, DestinationType destinationType, String destinationId,
                             String adminPath, String createdByAdminId, String reason, Instant createdAt) { }
    public record TimelineEntry(String eventId, Instant occurredAt, String eventType, String actorType,
                                String actorId, String actorDisplayName, String source,
                                String previousState, String newState, String reasonCode,
                                String reason, String correlationId, String requestId,
                                Map<String, String> safeMetadata) { }
    public record Capabilities(boolean canRead, boolean canClaim, boolean canRelease, boolean canRespond,
                               boolean canRequestInformation, boolean canAddNote, boolean canChangePriority,
                               boolean canLink, boolean canUnlink, boolean canResolve, boolean canEscalate,
                               boolean isAssignedToMe, boolean isAssignedToOther, boolean isWaitingForUser,
                               boolean isFinal, boolean isReadOnly, String readOnlyReason) { }
    public record AdminDetail(AdminSummary summary, RequesterSummary requester, String description,
                              List<AdminMessage> messages, List<InternalNote> internalNotes,
                              List<AdminLink> linkedEntities, List<Escalation> escalations,
                              Map<String, Object> linkedContext, List<TimelineEntry> timeline,
                              String resolutionCode, String resolutionReason, Instant resolvedAt,
                              Capabilities availableAdminCapabilities) { }
}
