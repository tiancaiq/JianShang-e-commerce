package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ReportContracts {
    private ReportContracts() { }

    public enum TargetType { USER, BUSINESS, LISTING, ORDER, MESSAGE, REVIEW }
    public enum ReasonCode { SCAM, COUNTERFEIT, PROHIBITED_ITEM, MISLEADING_LISTING, HARASSMENT, SPAM, IMPERSONATION, OTHER }
    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Status { SUBMITTED, UNDER_TRIAGE, DISMISSED, READY_FOR_INVESTIGATION, LINKED_TO_CASE, ACTIONED, WITHDRAWN }
    public enum Assignment { UNASSIGNED, ASSIGNED_TO_ME, ASSIGNED, ALL }
    public enum DismissalReason { NO_VIOLATION_FOUND, DUPLICATE, INSUFFICIENT_INFORMATION, INVALID_REPORT, TARGET_ALREADY_UNAVAILABLE, OTHER }

    public record CreateReportRequest(TargetType targetType, String targetId, ReasonCode reasonCode, String description) { }
    public record SubmissionResult(String reportId, Status status, Instant createdAt, String supportReference) { }

    public record Page(List<Summary> items, int page, int size, long totalElements, int totalPages, String sort) { }
    public record Summary(String reportId, TargetType targetType, String targetId, String safeTargetLabel,
                          ReasonCode reasonCode, Severity severity, Status status, String reporterUserId,
                          String assignedAdminId, Instant createdAt, Instant updatedAt, long version,
                          long relatedReportCount) { }
    public record ReporterSummary(String userId, String safeDisplayName) { }
    public record AdminSummary(String userId, String safeDisplayName) { }
    public record RelatedReport(String reportId, ReasonCode reasonCode, Severity severity, Status status, Instant createdAt) { }
    public record EnforcementSummary(String actionType, List<String> scopes) { }
    public record TimelineEntry(String eventId, Instant occurredAt, String eventType, String actorType,
                                String actorId, String actorDisplayName, String source, String previousState,
                                String newState, String reasonCode, String reason, String correlationId,
                                String requestId, Map<String, String> safeMetadata) { }
    public record Capabilities(boolean canRead, boolean canClaim, boolean canRelease, boolean canChangeSeverity,
                               boolean canDismiss, boolean canMarkReadyForInvestigation, boolean assignedToMe,
                               boolean assignedToOther, boolean resolved, boolean readOnly, String readOnlyReason,
                               boolean canCreateInvestigationCase, boolean canLinkInvestigationCase,
                               boolean canOpenInvestigationCase) { }
    public record InvestigationCaseLink(String caseId, String title, String status) { }
    public record Detail(String reportId, ReporterSummary reporterSummary, TargetType targetType, String targetId,
                         String safeTargetLabel, ReasonCode reasonCode, String description, Severity severity,
                         Status status, AdminSummary assignedAdmin, Instant createdAt, Instant updatedAt, long version,
                         JsonNode targetSnapshot, JsonNode currentTargetSummary, List<RelatedReport> relatedReports,
                         List<EnforcementSummary> currentEnforcementSummary, List<TimelineEntry> auditTimeline,
                         Capabilities availableAdminCapabilities, InvestigationCaseLink investigationCase) { }

    public record VersionRequest(Long expectedVersion) { }
    public record SeverityRequest(Severity severity, Long expectedVersion, String reason) { }
    public record DismissRequest(Long expectedVersion, DismissalReason reasonCode, String reason) { }
    public record ReadyRequest(Long expectedVersion, String reason) { }

    public record ListingTargetContext(String listingId, String sellerType, String individualSellerUserId,
                                       String businessId, String storeId, String title, String description,
                                       java.math.BigDecimal price, String currency, String categoryId, String sku,
                                       String status, List<String> imageReferences, Instant capturedAt,
                                       long version, boolean reportable, List<EnforcementSummary> enforcement) { }
}
