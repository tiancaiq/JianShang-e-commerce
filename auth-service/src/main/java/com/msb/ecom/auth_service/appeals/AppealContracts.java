package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.databind.JsonNode;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AppealContracts {
    private AppealContracts() { }

    public enum AppellantType { USER, BUSINESS_REPRESENTATIVE, LISTING_OWNER }
    public enum Status {
        SUBMITTED, UNDER_REVIEW, UPHOLD_RECOMMENDED, MODIFY_RECOMMENDED, REVOKE_RECOMMENDED,
        UPHELD, MODIFIED, REVOKED
    }
    public enum ReasonCode {
        DECISION_INCORRECT, NEW_EVIDENCE, ACCOUNT_COMPROMISED, MISIDENTIFICATION,
        ACTION_TOO_SEVERE, POLICY_MISAPPLIED, OTHER
    }
    public enum ReviewOutcome { UPHOLD_RECOMMENDED, MODIFY_RECOMMENDED, REVOKE_RECOMMENDED }
    public enum FinalOutcome { UPHELD, MODIFIED, REVOKED }
    public enum Assignment { UNASSIGNED, ASSIGNED_TO_ME, ASSIGNED, ALL }

    public record CreateAppealRequest(ReasonCode reasonCode, String explanation,
                                      List<String> safeEvidenceReferences) { }
    public record SubmissionResult(String appealId, Status status, Instant submittedAt,
                                   String supportReference) { }
    public record EnforcementNotice(String enforcementActionId, TargetType targetType, String targetId,
                                    String safeTargetLabel, ActionType actionType, Set<Scope> scopes,
                                    Instant effectiveAt, Instant expiresAt, String supportReference,
                                    boolean appealEligible, String appealIneligibilityReason,
                                    String appealId, Status appealStatus) { }
    public record MyAppeal(String appealId, String enforcementActionId, TargetType targetType,
                           String targetId, String safeTargetLabel, ActionType actionType, Status status,
                           Instant submittedAt, Instant updatedAt, String supportReference,
                           Instant resolvedAt, String safeOutcomeSummary,
                           String currentEffectiveEnforcementState) { }

    public record Page(List<Summary> items, int page, int size, long totalElements,
                       int totalPages, String sort) { }
    public record Summary(String appealId, String enforcementActionId, TargetType targetType,
                          String targetId, String safeTargetLabel, ActionType actionType,
                          ReasonCode appealReasonCode, Status status, String assignedAdminId,
                          Instant submittedAt, Instant updatedAt, long version) { }
    public record AdminSummary(String userId, String safeDisplayName) { }
    public record AppellantSummary(AppellantType appellantType, String userId, String safeDisplayName) { }
    public record EnforcementSummary(String enforcementActionId, TargetType targetType, String targetId,
                                     ActionType actionType, Set<Scope> scopes, String lifecycleState,
                                     Instant effectiveAt, Instant expiresAt, long version, Instant createdAt,
                                     String reasonCode, String reason, String caseId) { }
    public record OriginalCaseSummary(String caseId, String title, String status, String severity,
                                      Instant closedAt, String conclusionCode) { }
    public record LinkedReport(String reportId, String reasonCode, String severity, String status,
                               String safeTargetLabel, Instant createdAt) { }
    public record ReviewNote(String noteId, String body, String authorAdminId,
                             String authorDisplayName, Instant createdAt) { }
    public record TimelineEntry(String eventId, Instant occurredAt, String eventType, String actorType,
                                String actorId, String actorDisplayName, String source, String previousState,
                                String newState, String reasonCode, String reason, String correlationId,
                                String requestId, Map<String, String> safeMetadata) { }
    public record ReplacementProposal(TargetType targetType, String targetId, ActionType actionType,
                                      Set<Scope> scopes, Instant expiresAt, String reasonCode,
                                      String reason, Long expectedTargetVersion) { }
    public record Capabilities(boolean canRead, boolean canClaim, boolean canRelease,
                               boolean canStartReview, boolean canAddNote, boolean canReview,
                               boolean canRecommendUphold, boolean canRecommendModify,
                               boolean canRecommendRevoke, boolean isAssignedToMe,
                               boolean isAssignedToOther, boolean isFinalRecommendation,
                               boolean canResolveUphold, boolean canResolveModify,
                               boolean canResolveRevoke, boolean isFinalResolution,
                               boolean isReadOnly, String readOnlyReason) { }
    public record Detail(String appealId, String enforcementActionId, TargetType targetType, String targetId,
                         String safeTargetLabel, AppellantSummary appellantSummary, ReasonCode reasonCode,
                         String explanation, List<String> safeEvidenceReferences, Status status,
                         AdminSummary assignedAdmin, Instant submittedAt, Instant reviewStartedAt,
                         Instant reviewedAt, ReviewOutcome reviewOutcome, String reviewReasonCode,
                         String reviewReason, ReplacementProposal replacementProposal,
                         EnforcementSummary enforcementSummary, String currentEnforcementState,
                         Instant resolvedAt, FinalOutcome finalOutcome, String resolutionSummary,
                         EnforcementSummary replacementEnforcementSummary,
                         OriginalCaseSummary originalCaseSummary, List<LinkedReport> linkedReports,
                         JsonNode originalTargetSnapshotContext, JsonNode currentTargetSummary,
                         List<TimelineEntry> enforcementTimeline, List<TimelineEntry> caseTimeline,
                         List<TimelineEntry> appealTimeline, List<ReviewNote> internalReviewNotes,
                         Instant createdAt, Instant updatedAt, long version,
                         Capabilities availableAdminCapabilities) { }

    public record VersionRequest(Long expectedVersion) { }
    public record NoteRequest(Long expectedVersion, String body, String idempotencyKey) { }
    public record ReviewRequest(Long expectedVersion, ReviewOutcome outcome, String reasonCode,
                                String reason, ReplacementProposal replacementProposal) { }
    public record ResolutionPreviewRequest(Long expectedAppealVersion,
                                           Long expectedEnforcementVersion,
                                           Long expectedTargetVersion) { }
    public record ResolutionRequest(Long expectedAppealVersion, Long expectedEnforcementVersion,
                                    Long expectedTargetVersion, String previewToken,
                                    String idempotencyKey, Boolean confirmed) { }
    public record ResolutionPreview(FinalOutcome outcome, long appealVersion,
                                    long enforcementVersion, long targetVersion,
                                    String originalEnforcementActionId,
                                    ReplacementProposal replacementProposal,
                                    String predictedEffectiveEnforcementState,
                                    List<EffectiveRestriction> effectiveRestrictionsAfter,
                                    List<String> warnings, List<String> impactSummary,
                                    String previewToken, Instant expiresAt) { }
}
