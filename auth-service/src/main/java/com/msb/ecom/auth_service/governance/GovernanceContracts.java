package com.msb.ecom.auth_service.governance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class GovernanceContracts {
    private GovernanceContracts() { }

    public enum AssignmentStatus { ACTIVE, REVOKED, EXPIRED, SCHEDULED }
    public enum ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, EXECUTING, EXECUTED, FAILED, CANCELLED, INVALIDATED }
    public enum ApprovalDecision { APPROVE, REJECT }
    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }
    public enum ApprovalMode { NONE, SINGLE_APPROVAL, DUAL_APPROVAL }
    public enum ActionType {
        ADMIN_ROLE_GRANT_SUPER_ADMIN,
        ADMIN_ROLE_REVOKE_SUPER_ADMIN,
        LARGE_REFUND,
        HIGH_IMPACT_CATALOG_CATEGORY_DISABLE
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages, String sort) { }

    public record GovernanceDashboard(long admins, long pendingApprovals, long temporaryElevations,
                                      List<GovernanceEvent> recentActions,
                                      Map<String, Long> highRiskActionSummary) { }

    public record AdminSummary(String adminUserId, String safeDisplayName, String marketplaceAccountState,
                               String adminState, List<String> effectiveRoles,
                               boolean temporaryElevation, Instant lastGovernanceChange,
                               long governanceVersion) { }

    public record RoleAssignment(String assignmentId, String adminUserId, String role,
                                 AssignmentStatus status, Instant effectiveAt, Instant expiresAt,
                                 String grantedByAdminId, String reason, Instant createdAt,
                                 Instant revokedAt, String revokedByAdminId,
                                 String revocationReason, long version) { }

    public record RoleDefinition(String role, String description, boolean assignable,
                                 List<String> permissions) { }

    public record AdminCapabilities(boolean canManageRoles, boolean canManageElevation,
                                    boolean protectedAdmin, boolean lastSuperAdmin,
                                    boolean self, boolean readOnly, String readOnlyReason) { }

    public record AdminActivity(Instant timestamp, String actionType, String targetType,
                                String targetId, String correlationId, String result) { }

    public record AdminDetail(String adminUserId, String safeDisplayName,
                              String marketplaceAccountState, String adminState,
                              List<String> effectiveRoles, List<String> effectivePermissions,
                              List<RoleAssignment> activeAssignments,
                              List<RoleAssignment> historicalAssignments,
                              List<RoleAssignment> temporaryElevations,
                              List<AdminActivity> recentAdminActivity,
                              List<GovernanceEvent> governanceTimeline,
                              AdminCapabilities availableCapabilities,
                              long governanceVersion) { }

    public record GrantRoleRequest(String role, Instant effectiveAt, Instant expiresAt,
                                   String reason, Long expectedAdminGovernanceVersion,
                                   String idempotencyKey) { }

    public record RevokeRoleRequest(Long expectedAssignmentVersion, String reason,
                                    String idempotencyKey) { }

    public record RoleChangePreview(String adminUserId, String role, String changeType,
                                    List<String> currentRoles, List<String> proposedRoles,
                                    List<String> permissionsAdded, List<String> permissionsRemoved,
                                    Instant effectiveAt, Instant expiresAt, List<String> warnings,
                                    boolean protectedAdminImpact, boolean lastSuperAdminImpact,
                                    boolean approvalRequired, int requiredApprovals,
                                    long governanceVersion) { }

    public record RoleChangeResult(String outcome, RoleAssignment assignment,
                                   ApprovalSummary approval, boolean replayed) { }

    public record SensitiveActionPolicy(ActionType actionType, RiskLevel riskLevel,
                                        ApprovalMode approvalMode, int requiredApprovals,
                                        boolean requesterMayApprove, int approvalExpiresAfterMinutes,
                                        String requiredRequesterPermission,
                                        String requiredApproverPermission,
                                        BigDecimal thresholdValue, String thresholdCurrency,
                                        boolean enabled, long version) { }

    public record ApprovalSummary(String approvalId, ActionType actionType, RiskLevel riskLevel,
                                  String targetType, String targetId, String safeTargetLabel,
                                  String requesterAdminId, int requiredApprovals,
                                  int currentApprovals, ApprovalStatus status,
                                  Instant createdAt, Instant expiresAt, long version) { }

    public record ApprovalDecisionView(String decisionId, String approverAdminId,
                                       ApprovalDecision decision, String reason,
                                       Instant createdAt) { }

    public record GovernanceEvent(String eventId, String eventType, String actorAdminId,
                                  String subjectAdminId, String approvalRequestId,
                                  String targetType, String targetId, String reason,
                                  String outcome, Map<String, String> safeMetadata,
                                  String correlationId, Instant createdAt) { }

    public record ApprovalCapabilities(boolean canApprove, boolean canReject,
                                       boolean canExecute, boolean canCancel,
                                       boolean selfApprovalProhibited, boolean expired,
                                       boolean readOnly, String readOnlyReason) { }

    public record ApprovalDetail(ApprovalSummary approval, String safeActionSummary,
                                 Map<String, Object> safeActionPayload,
                                 SensitiveActionPolicy policy,
                                 List<ApprovalDecisionView> decisions,
                                 List<GovernanceEvent> timeline,
                                 ApprovalCapabilities availableCapabilities,
                                 String executionReference, String executionFailureCode,
                                 String executionFailureSummary, String executorAdminId,
                                 Instant executedAt) { }

    public record DecisionRequest(Long expectedVersion, String reason, String idempotencyKey) { }
    public record ExecuteRequest(Long expectedApprovalVersion, String idempotencyKey) { }
    public record CancelRequest(Long expectedVersion, String reason, String idempotencyKey) { }

    public record DomainApprovalResult(String outcome, ApprovalSummary approval,
                                       boolean approvalRequired, String message) { }

    public record RefundGovernanceRequest(String paymentId, String refundType,
                                          BigDecimal amount, String currency,
                                          String reasonCode, String reason, String disputeId,
                                          long expectedPaymentVersion, String idempotencyKey,
                                          String safeSummary) { }

    public record CatalogGovernanceRequest(String categoryId, String proposedStatus,
                                           long activeListingCount, long draftListingCount,
                                           long pendingListingCount, long expectedCategoryVersion,
                                           String reason, String idempotencyKey,
                                           String safeCategoryLabel) { }
}
