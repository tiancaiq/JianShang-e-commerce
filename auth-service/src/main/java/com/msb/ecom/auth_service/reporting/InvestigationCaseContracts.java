package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class InvestigationCaseContracts {
    private InvestigationCaseContracts() { }

    public enum Status { OPEN, UNDER_INVESTIGATION, READY_FOR_ACTION, CLOSED_NO_ACTION, CLOSED_ACTIONED }
    public enum RelationshipType { PRIMARY, RELATED }
    public enum Assignment { UNASSIGNED, ASSIGNED_TO_ME, ASSIGNED, ALL }
    public enum EvidenceType { REPORT_SNAPSHOT, CURRENT_TARGET_SNAPSHOT, EXISTING_ENFORCEMENT }
    public enum ReferenceType { REPORT, CASE_TARGET }
    public enum ConclusionCode {
        NO_VIOLATION_FOUND, INSUFFICIENT_EVIDENCE, FALSE_POSITIVE,
        DUPLICATE_INVESTIGATION, TARGET_ALREADY_RESOLVED, OTHER
    }
    public enum ProposalStatus { DRAFT, VALIDATED, EXECUTED, FAILED, CANCELLED }

    public record CreateRequest(String title, ReportContracts.Severity severity, Long expectedReportVersion) { }
    public record VersionRequest(Long expectedVersion) { }
    public record ReasonRequest(Long expectedVersion, String reason) { }
    public record SeverityRequest(Long expectedVersion, ReportContracts.Severity severity, String reason) { }
    public record CloseRequest(Long expectedVersion, ConclusionCode conclusionCode, String reason) { }
    public record LinkReportRequest(String reportId, Long expectedCaseVersion, Long expectedReportVersion) { }
    public record UnlinkReportRequest(Long expectedCaseVersion, Long expectedReportVersion, String reason) { }
    public record LinkTargetRequest(ReportContracts.TargetType targetType, String targetId,
                                    RelationshipType relationshipType, Long expectedCaseVersion) { }
    public record UnlinkTargetRequest(Long expectedCaseVersion, String reason) { }
    public record AddNoteRequest(Long expectedVersion, String body, String idempotencyKey) { }
    public record AddEvidenceRequest(Long expectedVersion, EvidenceType evidenceType, ReferenceType referenceType,
                                     String referenceId, String label) { }
    public record CreateProposalRequest(ReportContracts.TargetType targetType, String targetId,
                                        ActionType actionType, java.util.Set<Scope> scopes,
                                        String reasonCode, String reason, Instant effectiveAt, Instant expiresAt,
                                        Long expectedCaseVersion, Long expectedTargetVersion) { }
    public record UpdateProposalRequest(ReportContracts.TargetType targetType, String targetId,
                                        ActionType actionType, java.util.Set<Scope> scopes,
                                        String reasonCode, String reason, Instant effectiveAt, Instant expiresAt,
                                        Long expectedCaseVersion, Long expectedTargetVersion,
                                        Long expectedProposalVersion) { }
    public record ProposalVersionRequest(Long expectedCaseVersion, Long expectedProposalVersion) { }
    public record ExecuteProposalRequest(Long expectedCaseVersion, Long expectedProposalVersion,
                                         String idempotencyKey) { }
    public record CancelProposalRequest(Long expectedCaseVersion, Long expectedProposalVersion, String reason) { }
    public record CloseActionedRequest(Long expectedCaseVersion, String reason) { }

    public record Page(List<Summary> items, int page, int size, long totalElements, int totalPages, String sort) { }
    public record Summary(String caseId, String title, Status status, ReportContracts.Severity severity,
                          ReportContracts.TargetType primaryTargetType, String primaryTargetId,
                          String safePrimaryTargetLabel, String assignedAdminId, long linkedReportCount,
                          long linkedTargetCount, long noteCount, Instant createdAt, Instant updatedAt, long version) { }
    public record AdminSummary(String userId, String safeDisplayName) { }
    public record Target(ReportContracts.TargetType targetType, String targetId, String safeTargetLabel,
                         RelationshipType relationshipType, Instant linkedAt, JsonNode currentState,
                         List<ReportContracts.EnforcementSummary> currentEnforcement,
                         String adminDetailPath) { }
    public record LinkedReport(String reportId, ReportContracts.ReasonCode reasonCode,
                               ReportContracts.Severity severity, ReportContracts.Status status,
                               String safeTargetLabel, String description, JsonNode targetSnapshot,
                               Instant createdAt, long version) { }
    public record Note(String noteId, String body, String authorAdminId, String authorDisplayName,
                       Instant createdAt, String correlationId) { }
    public record Evidence(String evidenceId, EvidenceType evidenceType, ReferenceType referenceType,
                           String referenceId, String label, JsonNode snapshotMetadata,
                           String addedByAdminId, Instant createdAt) { }
    public record TimelineEntry(String eventId, Instant occurredAt, String eventType, String actorType,
                                String actorId, String actorDisplayName, String source, String previousState,
                                String newState, String reasonCode, String reason, String correlationId,
                                String requestId, Map<String, String> safeMetadata) { }
    public record ProposalCapabilities(boolean canEdit, boolean canCancel, boolean canDryRun,
                                       boolean canExecute, boolean canRetry,
                                       boolean hasRequiredTargetPermission) { }
    public record Proposal(String proposalId, ReportContracts.TargetType targetType, String targetId,
                           String safeTargetLabel, ActionType actionType, java.util.Set<Scope> scopes,
                           String reasonCode, String reason, Instant effectiveAt, Instant expiresAt,
                           long expectedTargetVersion, ProposalStatus status, String createdByAdminId,
                           Instant createdAt, Instant updatedAt, long version, Instant dryRunValidatedAt,
                           Long dryRunTargetVersion, JsonNode dryRunResult, String resultingEnforcementActionId,
                           String executionErrorCode, String executionErrorSummary,
                           String correlationId, ProposalCapabilities availableAdminCapabilities) { }
    public record EnforcementLink(String proposalId, ReportContracts.TargetType targetType, String targetId,
                                  String enforcementActionId, Instant executedAt,
                                  String executedByAdminId, String correlationId) { }
    public record Capabilities(boolean canRead, boolean canClaim, boolean canRelease, boolean canStartInvestigation,
                               boolean canLinkReport, boolean canUnlinkReport, boolean canLinkTarget,
                               boolean canUnlinkTarget, boolean canAddNote, boolean canAddEvidence,
                               boolean canChangeSeverity, boolean canMarkReadyForAction,
                               boolean canCloseNoAction, boolean canCreateEnforcementProposal,
                               boolean canCloseActioned, boolean isCaseReadyForAction,
                               boolean isAssignedToMe, boolean isAssignedToOther,
                               boolean isClosed, boolean isReadOnly, String readOnlyReason) { }
    public record Detail(String caseId, String title, Status status, ReportContracts.Severity severity,
                         Target primaryTarget, List<Target> linkedTargets, List<LinkedReport> linkedReports,
                         AdminSummary assignedAdmin, List<Note> notes, List<Evidence> evidence,
                         List<Proposal> enforcementProposals, List<EnforcementLink> resultingEnforcement,
                         List<TimelineEntry> caseTimeline, String conclusionCode, String conclusionReason,
                         Instant readyForActionAt, Instant closedAt, Instant createdAt, Instant updatedAt,
                         long version, Capabilities availableAdminCapabilities) { }
}
