package com.msb.ecom.auth_service.governance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.msb.ecom.auth_service.governance.GovernanceContracts.*;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AdminGovernanceService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern ID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Set<String> ASSIGNABLE_ROLES = Set.of(
            "SUPER_ADMIN", "TRUST_AND_SAFETY_ADMIN", "BUSINESS_REVIEWER",
            "LISTING_MODERATOR", "SUPPORT_ADMIN", "USER_RESTRICTOR",
            "BUSINESS_RESTRICTOR", "CATALOG_ADMIN", "OPERATIONS_ADMIN",
            "GOVERNANCE_ADMIN", "AUDITOR");
    private static final Set<String> ADMIN_STATUSES = Set.of("ADMIN_ENABLED", "ADMIN_DISABLED");

    private final GovernanceRepository repository;
    private final AuthService authService;
    private final AdminAuthorizationService authorization;
    private final CurrentActorProvider currentActorProvider;
    private final GovernanceDomainExecutor domainExecutor;
    private final UlidGenerator ids;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AdminGovernanceService(GovernanceRepository repository, AuthService authService,
                                  AdminAuthorizationService authorization,
                                  CurrentActorProvider currentActorProvider,
                                  GovernanceDomainExecutor domainExecutor, UlidGenerator ids,
                                  ObjectMapper mapper, Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.authService = authService;
        this.authorization = authorization;
        this.currentActorProvider = currentActorProvider;
        this.domainExecutor = domainExecutor;
        this.ids = ids;
        this.mapper = mapper;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public GovernanceDashboard dashboard() {
        actor(AdminPermission.GOVERNANCE_READ);
        Instant now = clock.instant();
        List<GovernanceEvent> recent = repository.recentEvents(12).stream().map(this::event).toList();
        Map<String, Long> risks = recent.stream()
                .filter(value -> value.approvalRequestId() != null)
                .collect(Collectors.groupingBy(GovernanceEvent::outcome,
                        LinkedHashMap::new, Collectors.counting()));
        return new GovernanceDashboard(repository.activeAdminCount(now),
                repository.pendingApprovalCount(now), repository.temporaryElevationCount(now),
                recent, risks);
    }

    @Transactional(readOnly = true)
    public Page<AdminSummary> admins(String q, String role, String status, Boolean temporary,
                                     int page, int size, String sort) {
        actor(AdminPermission.GOVERNANCE_READ);
        String query = optionalText(q, 120);
        String roleFilter = role == null || role.isBlank() ? null : validRole(role);
        String state = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (state != null && !ADMIN_STATUSES.contains(state)) {
            throw GovernanceException.invalid("GOVERNANCE_ADMIN_STATUS_INVALID", "Admin status is invalid.");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        if (sort != null && !sort.isBlank() && !"name,asc".equals(sort)) {
            throw GovernanceException.invalid("GOVERNANCE_SORT_INVALID", "Admin governance sort is invalid.");
        }
        Instant now = clock.instant();
        long governanceVersion = repository.governanceVersion(false);
        List<GovernanceRepository.AdminRow> rows = repository.admins(query, roleFilter, state, temporary,
                safePage, safeSize, now);
        Map<String, List<String>> effectiveRoles = repository.effectiveRoles(
                rows.stream().map(GovernanceRepository.AdminRow::id).toList(), now);
        List<AdminSummary> items = rows.stream()
                .map(value -> new AdminSummary(value.id(), value.displayName(), value.accountState(),
                        value.enabled() ? "ADMIN_ENABLED" : "ADMIN_DISABLED",
                        displayRoles(effectiveRoles.getOrDefault(value.id(), List.of())), value.temporary(),
                        value.lastChange(), governanceVersion)).toList();
        long total = repository.adminCount(query, roleFilter, state, temporary, now);
        return new Page<>(items, safePage, safeSize, total, pages(total, safeSize), "name,asc");
    }

    @Transactional(readOnly = true)
    public AdminDetail admin(String rawAdminId) {
        User actor = actor(AdminPermission.GOVERNANCE_READ);
        String adminId = id(rawAdminId, "Admin ID");
        GovernanceRepository.UserRow target = repository.user(adminId)
                .orElseThrow(() -> GovernanceException.notFound("Admin was not found."));
        List<GovernanceRepository.AssignmentRow> rows = repository.assignments(adminId, false);
        if (rows.isEmpty()) throw GovernanceException.notFound("Admin was not found.");
        Instant now = clock.instant();
        List<RoleAssignment> assignments = rows.stream().map(value -> assignment(value, now)).toList();
        List<RoleAssignment> active = assignments.stream()
                .filter(value -> value.status() == AssignmentStatus.ACTIVE
                        || value.status() == AssignmentStatus.SCHEDULED).toList();
        List<RoleAssignment> historical = assignments.stream()
                .filter(value -> value.status() == AssignmentStatus.REVOKED
                        || value.status() == AssignmentStatus.EXPIRED).toList();
        List<RoleAssignment> temporary = assignments.stream()
                .filter(value -> value.expiresAt() != null
                        && (value.status() == AssignmentStatus.ACTIVE
                        || value.status() == AssignmentStatus.SCHEDULED)).toList();
        List<String> roles = displayRoles(repository.effectiveRoles(adminId, now));
        List<String> permissions = repository.effectivePermissions(adminId, now);
        long superAdmins = repository.effectiveSuperAdmins(now);
        boolean isSuper = roles.contains("SUPER_ADMIN");
        AdminCapabilities capabilities = new AdminCapabilities(
                has(actor, AdminPermission.GOVERNANCE_ROLES_MANAGE),
                has(actor, AdminPermission.GOVERNANCE_ELEVATION_MANAGE),
                "SERVICE".equals(target.accountType()), isSuper && superAdmins == 1,
                actor.getId().equals(adminId),
                !has(actor, AdminPermission.GOVERNANCE_ROLES_MANAGE),
                has(actor, AdminPermission.GOVERNANCE_ROLES_MANAGE)
                        ? null : "Your role can inspect governance but cannot change role assignments.");
        List<GovernanceEvent> timeline = repository.eventsForAdmin(adminId, 50).stream().map(this::event).toList();
        List<AdminActivity> activity = timeline.stream().limit(25)
                .map(value -> new AdminActivity(value.createdAt(), value.eventType(),
                        value.targetType(), value.targetId(), value.correlationId(), value.outcome()))
                .toList();
        return new AdminDetail(adminId, target.displayName(), target.status(),
                roles.isEmpty() ? "ADMIN_DISABLED" : "ADMIN_ENABLED", roles, permissions,
                active, historical, temporary, activity, timeline, capabilities,
                repository.governanceVersion(false));
    }

    @Transactional(readOnly = true)
    public List<RoleDefinition> roles() {
        actor(AdminPermission.GOVERNANCE_ROLES_READ);
        Map<String, List<GovernanceRepository.RoleRow>> grouped = repository.roles().stream()
                .collect(Collectors.groupingBy(GovernanceRepository.RoleRow::role,
                        LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().map(entry -> new RoleDefinition(entry.getKey(),
                entry.getValue().getFirst().description(), ASSIGNABLE_ROLES.contains(entry.getKey()),
                entry.getValue().stream().map(GovernanceRepository.RoleRow::permission)
                        .filter(Objects::nonNull).toList())).toList();
    }

    @Transactional(readOnly = true)
    public RoleChangePreview previewGrant(String rawAdminId, GrantRoleRequest request) {
        User actor = roleManager(request != null && request.expiresAt() != null);
        String adminId = targetAdmin(rawAdminId);
        ValidGrant grant = validateGrant(adminId, request);
        return grantPreview(actor, adminId, grant);
    }

    public RoleChangeResult grant(String rawAdminId, GrantRoleRequest request, String correlationId) {
        User actor = roleManager(request != null && request.expiresAt() != null);
        String adminId = targetAdmin(rawAdminId);
        RoleAssignment replay = replayedGrant(actor, adminId, request);
        if (replay != null) return new RoleChangeResult("ROLE_GRANTED", replay, null, true);
        ValidGrant grant = validateGrant(adminId, request);
        RoleChangePreview preview = grantPreview(actor, adminId, grant);
        if (preview.approvalRequired()) {
            ApprovalSummary approval = requestApproval(actor, ActionType.ADMIN_ROLE_GRANT_SUPER_ADMIN,
                    "ADMIN_ROLE_ASSIGNMENT", adminId,
                    Map.of("adminUserId", adminId, "role", grant.role(),
                            "effectiveAt", grant.effectiveAt().toString(),
                            "expiresAt", grant.expiresAt() == null ? "" : grant.expiresAt().toString(),
                            "reason", grant.reason(), "governanceVersion", grant.governanceVersion()),
                    "Grant SUPER_ADMIN to " + safeAdminLabel(adminId), null,
                    grant.idempotencyKey(), correlation(correlationId));
            return new RoleChangeResult("PENDING_APPROVAL", null, approval, false);
        }
        RoleAssignment assignment = transactions.execute(status -> directGrant(actor, adminId, grant,
                correlation(correlationId), false));
        return new RoleChangeResult("ROLE_GRANTED", assignment, null, false);
    }

    @Transactional(readOnly = true)
    public RoleChangePreview previewRevoke(String rawAdminId, String assignmentId,
                                           RevokeRoleRequest request) {
        User actor = roleManager(false);
        String adminId = targetAdmin(rawAdminId);
        GovernanceRepository.AssignmentRow assignment = assignmentForAdmin(adminId, assignmentId, false);
        return revokePreview(actor, assignment, request);
    }

    public RoleChangeResult revoke(String rawAdminId, String assignmentId,
                                   RevokeRoleRequest request, String correlationId) {
        User actor = roleManager(false);
        String adminId = targetAdmin(rawAdminId);
        GovernanceRepository.AssignmentRow assignment = assignmentForAdmin(adminId, assignmentId, false);
        ValidRevoke revoke = validateRevoke(request);
        String requestHash = revokeHash(assignment.id(), revoke);
        if ("REVOKED".equals(assignment.status())) {
            if (actor.getId().equals(assignment.revokedBy())
                    && revoke.idempotencyKey().equals(assignment.revocationIdempotencyKey())
                    && requestHash.equals(assignment.revocationRequestHash())) {
                return new RoleChangeResult("ROLE_REVOKED", assignment(assignment, clock.instant()), null, true);
            }
            throw versionConflict();
        }
        RoleChangePreview preview = revokePreview(actor, assignment, request);
        if (preview.approvalRequired()) {
            ApprovalSummary approval = requestApproval(actor, ActionType.ADMIN_ROLE_REVOKE_SUPER_ADMIN,
                    "ADMIN_ROLE_ASSIGNMENT", assignment.id(),
                    Map.of("adminUserId", adminId, "assignmentId", assignment.id(),
                            "role", assignment.role(), "assignmentVersion", revoke.expectedVersion(),
                            "reason", revoke.reason()),
                    "Revoke SUPER_ADMIN from " + safeAdminLabel(adminId), assignment.version(),
                    revoke.idempotencyKey(), correlation(correlationId));
            return new RoleChangeResult("PENDING_APPROVAL", null, approval, false);
        }
        RoleAssignment result = transactions.execute(status -> directRevoke(actor, assignment,
                revoke, correlation(correlationId), false));
        return new RoleChangeResult("ROLE_REVOKED", result, null, false);
    }

    public DomainApprovalResult evaluateRefund(RefundGovernanceRequest request, String correlationId) {
        User requester = actor(AdminPermission.REFUND_EXECUTE);
        require(requester, AdminPermission.GOVERNANCE_APPROVAL_REQUEST);
        if (request == null) throw GovernanceException.invalid("GOVERNANCE_REFUND_INVALID", "Refund governance details are required.");
        String paymentId = id(request.paymentId(), "Payment ID");
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw GovernanceException.invalid("GOVERNANCE_REFUND_INVALID", "Refund amount must be greater than zero.");
        }
        SensitiveActionPolicy policy = policy(ActionType.LARGE_REFUND);
        boolean thresholdApplies = policy.enabled() && policy.thresholdValue() != null
                && amount.compareTo(policy.thresholdValue()) >= 0
                && (policy.thresholdCurrency() == null
                    || policy.thresholdCurrency().equalsIgnoreCase(requiredText(request.currency(), "Currency", 3)));
        if (!thresholdApplies) {
            return new DomainApprovalResult("APPROVAL_NOT_REQUIRED", null, false,
                    "The refund is below the configured governance threshold.");
        }
        String key = key(request.idempotencyKey());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", paymentId);
        payload.put("refundType", requiredText(request.refundType(), "Refund type", 16));
        payload.put("amount", amount);
        payload.put("currency", requiredText(request.currency(), "Currency", 3));
        payload.put("reasonCode", requiredText(request.reasonCode(), "Reason code", 64));
        payload.put("reason", optionalText(request.reason(), 1000));
        payload.put("disputeId", request.disputeId() == null ? "" : id(request.disputeId(), "Dispute ID"));
        payload.put("expectedPaymentVersion", request.expectedPaymentVersion());
        ApprovalSummary approval = requestApproval(requester, ActionType.LARGE_REFUND,
                "PAYMENT", paymentId, payload,
                request.safeSummary() == null ? "Large refund for payment " + paymentId
                        : requiredText(request.safeSummary(), "Safe summary", 1000),
                request.expectedPaymentVersion(), key, correlation(correlationId));
        return new DomainApprovalResult("PENDING_APPROVAL", approval, true,
                "The refund requires independent approval before execution.");
    }

    public DomainApprovalResult evaluateCatalogDisable(CatalogGovernanceRequest request,
                                                        String correlationId) {
        User requester = actor(AdminPermission.CATALOG_POLICY_MANAGE);
        require(requester, AdminPermission.GOVERNANCE_APPROVAL_REQUEST);
        if (request == null || !"DISABLED".equals(request.proposedStatus())) {
            return new DomainApprovalResult("APPROVAL_NOT_REQUIRED", null, false,
                    "Only category disablement is governed by this policy.");
        }
        String categoryId = id(request.categoryId(), "Category ID");
        SensitiveActionPolicy policy = policy(ActionType.HIGH_IMPACT_CATALOG_CATEGORY_DISABLE);
        boolean thresholdApplies = policy.enabled() && policy.thresholdValue() != null
                && BigDecimal.valueOf(request.activeListingCount()).compareTo(policy.thresholdValue()) >= 0;
        if (!thresholdApplies) {
            return new DomainApprovalResult("APPROVAL_NOT_REQUIRED", null, false,
                    "The category impact is below the configured governance threshold.");
        }
        Map<String, Object> payload = Map.of(
                "categoryId", categoryId, "proposedStatus", "DISABLED",
                "activeListingCount", request.activeListingCount(),
                "draftListingCount", request.draftListingCount(),
                "pendingListingCount", request.pendingListingCount(),
                "expectedCategoryVersion", request.expectedCategoryVersion(),
                "reason", requiredText(request.reason(), "Reason", 1000));
        ApprovalSummary approval = requestApproval(requester,
                ActionType.HIGH_IMPACT_CATALOG_CATEGORY_DISABLE, "CATEGORY", categoryId,
                payload, "Disable high-impact category "
                        + requiredText(request.safeCategoryLabel(), "Safe category label", 200),
                request.expectedCategoryVersion(), key(request.idempotencyKey()),
                correlation(correlationId));
        return new DomainApprovalResult("PENDING_APPROVAL", approval, true,
                "The category change requires independent approval before execution.");
    }

    @Transactional(readOnly = true)
    public Page<ApprovalSummary> approvals(String status, String risk, String action,
                                           String requester, String targetType,
                                           int page, int size, String sort) {
        actor(AdminPermission.GOVERNANCE_APPROVAL_READ);
        String normalizedStatus = enumFilter(status, ApprovalStatus.class, "Approval status");
        String normalizedRisk = enumFilter(risk, RiskLevel.class, "Risk level");
        String normalizedAction = enumFilter(action, ActionType.class, "Action type");
        String requesterId = requester == null || requester.isBlank() ? null : id(requester, "Requester ID");
        String target = optionalText(targetType, 48);
        int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(100, size));
        if (sort != null && !sort.isBlank() && !"createdAt,desc".equals(sort)) {
            throw GovernanceException.invalid("GOVERNANCE_SORT_INVALID", "Approval sort is invalid.");
        }
        Instant now = clock.instant();
        List<ApprovalSummary> items = repository.approvals(normalizedStatus, normalizedRisk,
                normalizedAction, requesterId, target, safePage, safeSize, now).stream()
                .map(this::summary).toList();
        long total = repository.approvalCount(normalizedStatus, normalizedRisk,
                normalizedAction, requesterId, target, now);
        return new Page<>(items, safePage, safeSize, total, pages(total, safeSize), "createdAt,desc");
    }

    @Transactional(readOnly = true)
    public ApprovalDetail approval(String rawId) {
        User actor = actor(AdminPermission.GOVERNANCE_APPROVAL_READ);
        GovernanceRepository.ApprovalRow row = repository.approval(id(rawId, "Approval ID"), false)
                .orElseThrow(() -> GovernanceException.notFound("Approval request was not found."));
        return detail(actor, row);
    }

    @Transactional
    public ApprovalDetail decide(String rawId, ApprovalDecision decision, DecisionRequest request,
                                 String correlationId) {
        User approver = actor(AdminPermission.GOVERNANCE_APPROVAL_REVIEW);
        ValidDecision input = validateDecision(request);
        String approvalId = id(rawId, "Approval ID");
        String hash = hash(approvalId + "|" + decision + "|" + input.reason()
                + "|" + input.expectedVersion());
        GovernanceRepository.DecisionRow replay = repository.decisionByKey(approver.getId(), input.key()).orElse(null);
        if (replay != null) {
            if (!replay.requestHash().equals(hash) || !replay.approvalId().equals(approvalId)) {
                throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was reused with changed decision content.");
            }
            return detail(approver, repository.approval(approvalId, false).orElseThrow());
        }
        GovernanceRepository.ApprovalRow row = repository.approval(approvalId, true)
                .orElseThrow(() -> GovernanceException.notFound("Approval request was not found."));
        row = requirePending(row, input.expectedVersion());
        SensitiveActionPolicy policy = policy(ActionType.valueOf(row.actionType()));
        if (approver.getId().equals(row.requesterId()) && !policy.requesterMayApprove()) {
            throw GovernanceException.forbidden("GOVERNANCE_SELF_APPROVAL_PROHIBITED",
                    "The requester cannot approve or reject this action as an independent reviewer.");
        }
        requirePermissionId(approver, policy.requiredApproverPermission());
        if (!repository.insertDecision(new GovernanceRepository.DecisionInsert(ids.next(), approvalId,
                approver.getId(), decision.name(), input.reason(), input.key(), hash, clock.instant()))) {
            throw GovernanceException.conflict("GOVERNANCE_DUPLICATE_DECISION",
                    "This administrator has already reviewed the approval request.");
        }
        Instant now = clock.instant();
        String nextStatus = decision == ApprovalDecision.REJECT ? "REJECTED"
                : repository.approvalDecisionCount(approvalId) >= row.requiredApprovals() ? "APPROVED" : "PENDING";
        if (!"PENDING".equals(nextStatus)
                && !repository.transitionApproval(approvalId, row.version(), "PENDING", nextStatus, now)) {
            throw versionConflict();
        }
        audit(decision == ApprovalDecision.APPROVE ? "SENSITIVE_ACTION_APPROVED" : "SENSITIVE_ACTION_REJECTED",
                approver.getId(), row.requesterId(), approvalId, row.targetType(), row.targetId(),
                input.reason(), nextStatus, Map.of("actionType", row.actionType()),
                correlation(correlationId), now);
        return detail(approver, repository.approval(approvalId, false).orElseThrow());
    }

    @Transactional
    public ApprovalDetail cancel(String rawId, CancelRequest request, String correlationId) {
        User actor = actor(AdminPermission.GOVERNANCE_APPROVAL_READ);
        if (request == null || request.expectedVersion() == null) throw versionConflict();
        String reason = requiredText(request.reason(), "Reason", 1000);
        String idempotencyKey = key(request.idempotencyKey());
        String approvalId = id(rawId, "Approval ID");
        String requestHash = hash(approvalId + "|" + reason + "|" + request.expectedVersion());
        GovernanceRepository.ApprovalRow row = repository.approval(approvalId, true)
                .orElseThrow(() -> GovernanceException.notFound("Approval request was not found."));
        if (!actor.getId().equals(row.requesterId())) {
            throw GovernanceException.forbidden("GOVERNANCE_CANCEL_FORBIDDEN",
                    "Only the requester can cancel a pending approval request.");
        }
        if ("CANCELLED".equals(row.status())) {
            if (idempotencyKey.equals(row.cancellationIdempotencyKey())
                    && requestHash.equals(row.cancellationRequestHash())) return detail(actor, row);
            throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                    "The cancellation idempotency key was reused with changed content.");
        }
        row = requirePending(row, request.expectedVersion());
        Instant now = clock.instant();
        if (!repository.cancelApproval(approvalId, row.version(), actor.getId(),
                idempotencyKey, requestHash, now)) {
            throw versionConflict();
        }
        audit("SENSITIVE_ACTION_CANCELLED", actor.getId(), row.requesterId(), approvalId,
                row.targetType(), row.targetId(), reason, "CANCELLED",
                Map.of("actionType", row.actionType()), correlation(correlationId), now);
        return detail(actor, repository.approval(approvalId, false).orElseThrow());
    }

    public ApprovalDetail execute(String rawId, ExecuteRequest request, String correlationId) {
        User executor = actor(AdminPermission.GOVERNANCE_APPROVAL_READ);
        if (request == null || request.expectedApprovalVersion() == null) throw versionConflict();
        String key = key(request.idempotencyKey());
        String approvalId = id(rawId, "Approval ID");
        GovernanceRepository.ApprovalRow initial = repository.approval(approvalId, false)
                .orElseThrow(() -> GovernanceException.notFound("Approval request was not found."));
        if ("EXECUTED".equals(initial.status()) && key.equals(initial.executionIdempotencyKey())) {
            return detail(executor, initial);
        }
        SensitiveActionPolicy policy = policy(ActionType.valueOf(initial.actionType()));
        requirePermissionId(executor, policy.requiredRequesterPermission());
        GovernanceDomainExecutor.Validation validation = revalidate(initial, executor);
        if (!validation.valid() || !fingerprintMaterial(validation.fingerprintMaterial()).equals(initial.fingerprint())) {
            transactions.executeWithoutResult(status -> {
                GovernanceRepository.ApprovalRow locked = repository.approval(approvalId, true).orElseThrow();
                repository.transitionApproval(approvalId, locked.version(), locked.status(), "INVALIDATED", clock.instant());
                audit("SENSITIVE_ACTION_INVALIDATED", executor.getId(), locked.requesterId(), approvalId,
                        locked.targetType(), locked.targetId(),
                        validation.denialSummary() == null ? "Approved action became stale." : validation.denialSummary(),
                        "INVALIDATED", Map.of("actionType", locked.actionType()),
                        correlation(correlationId), clock.instant());
            });
            throw GovernanceException.conflict("GOVERNANCE_APPROVAL_STALE",
                    "The approved action no longer matches current domain state. Create a new request.");
        }
        transactions.executeWithoutResult(status -> {
            GovernanceRepository.ApprovalRow locked = repository.approval(approvalId, true)
                    .orElseThrow(() -> GovernanceException.notFound("Approval request was not found."));
            requireExecutable(locked, request.expectedApprovalVersion(), key);
            if (!repository.startExecution(approvalId, locked.version(), executor.getId(), key, clock.instant())) {
                throw versionConflict();
            }
            audit("SENSITIVE_ACTION_EXECUTION_STARTED", executor.getId(), locked.requesterId(), approvalId,
                    locked.targetType(), locked.targetId(), "Approved action execution started.", "EXECUTING",
                    Map.of("actionType", locked.actionType()), correlation(correlationId), clock.instant());
        });
        GovernanceDomainExecutor.Execution result;
        GovernanceRepository.ApprovalRow executing = repository.approval(approvalId, false).orElseThrow();
        try {
            if (executing.actionType().startsWith("ADMIN_ROLE_")) {
                result = transactions.execute(status -> executeRoleAction(
                        executor, executing, correlation(correlationId)));
            } else {
                result = domainExecutor.execute(executing.actionType(), executing.targetId(), executing.payload(),
                        currentActorProvider.currentActor().accessToken(), "governance-" + approvalId,
                        correlation(correlationId));
            }
        } catch (RuntimeException exception) {
            result = GovernanceDomainExecutor.Execution.failed("GOVERNED_DOMAIN_EXECUTION_FAILED",
                    "The owning domain did not accept the approved action.");
        }
        GovernanceDomainExecutor.Execution finalResult = result;
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            GovernanceRepository.ApprovalRow locked = repository.approval(approvalId, true).orElseThrow();
            if (finalResult.succeeded()) {
                repository.completeExecution(approvalId, finalResult.reference(), now);
                audit("SENSITIVE_ACTION_EXECUTED", executor.getId(), locked.requesterId(), approvalId,
                        locked.targetType(), locked.targetId(), "Approved action executed.", "EXECUTED",
                        Map.of("actionType", locked.actionType(), "executionReference", finalResult.reference()),
                        correlation(correlationId), now);
            } else {
                repository.failExecution(approvalId, finalResult.failureCode(), finalResult.failureSummary(), now);
                audit("SENSITIVE_ACTION_EXECUTION_FAILED", executor.getId(), locked.requesterId(), approvalId,
                        locked.targetType(), locked.targetId(), finalResult.failureSummary(), "FAILED",
                        Map.of("actionType", locked.actionType(), "failureCode", finalResult.failureCode()),
                        correlation(correlationId), now);
            }
        });
        return detail(executor, repository.approval(approvalId, false).orElseThrow());
    }

    private RoleAssignment directGrant(User actor, String adminId, ValidGrant grant,
                                       String correlationId, boolean approved) {
        long currentVersion = repository.governanceVersion(true);
        GovernanceRepository.AssignmentRow replay = repository.assignmentRequest(actor.getId(), grant.idempotencyKey()).orElse(null);
        String requestHash = hash(adminId + "|" + grant.role() + "|" + grant.effectiveAt()
                + "|" + grant.expiresAt() + "|" + grant.reason());
        if (replay != null) {
            if (!requestHash.equals(replay.requestHash())) {
                throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was reused with changed role assignment content.");
            }
            return assignment(replay, clock.instant());
        }
        if (currentVersion != grant.governanceVersion()) throw versionConflict();
        if (repository.overlappingAssignment(adminId, grant.role(), grant.effectiveAt(), grant.expiresAt())) {
            throw GovernanceException.conflict("GOVERNANCE_ROLE_OVERLAP",
                    "An active or scheduled overlapping role assignment already exists.");
        }
        Instant now = clock.instant();
        String assignmentId = ids.next();
        repository.insertAssignment(new GovernanceRepository.AssignmentInsert(assignmentId, adminId,
                grant.role(), grant.effectiveAt(), grant.expiresAt(), actor.getId(), grant.reason(),
                grant.idempotencyKey(), requestHash, correlationId, now));
        if (!repository.bumpGovernanceVersion(currentVersion, now)) throw versionConflict();
        String eventType = grant.expiresAt() == null ? "ADMIN_ROLE_GRANTED" : "ADMIN_ELEVATION_GRANTED";
        audit(eventType, actor.getId(), adminId, null, "ADMIN_ROLE_ASSIGNMENT", assignmentId,
                grant.reason(), "ACTIVE", Map.of("role", grant.role(), "approved", String.valueOf(approved)),
                correlationId, now);
        return assignment(repository.assignment(assignmentId, false).orElseThrow(), now);
    }

    private RoleAssignment directRevoke(User actor, GovernanceRepository.AssignmentRow original,
                                        ValidRevoke revoke, String correlationId, boolean approved) {
        long governanceVersion = repository.governanceVersion(true);
        GovernanceRepository.AssignmentRow assignment = repository.assignment(original.id(), true)
                .orElseThrow(() -> GovernanceException.notFound("Role assignment was not found."));
        if (!"ACTIVE".equals(assignment.status())) {
            if (actor.getId().equals(assignment.revokedBy())
                    && revoke.idempotencyKey().equals(assignment.revocationIdempotencyKey())
                    && revokeHash(assignment.id(), revoke).equals(assignment.revocationRequestHash())) {
                return assignment(assignment, clock.instant());
            }
            throw versionConflict();
        }
        if (assignment.version() != revoke.expectedVersion()) throw versionConflict();
        Instant now = clock.instant();
        if ("SUPER_ADMIN".equals(assignment.role()) && effective(assignment, now)
                && !repository.permanentEffectiveSuperAdminExistsExcludingLocked(assignment.id(), now)) {
            throw GovernanceException.conflict("GOVERNANCE_LAST_SUPER_ADMIN",
                    "The final active SUPER_ADMIN assignment cannot be revoked.");
        }
        if (!repository.revokeAssignment(assignment.id(), assignment.version(), actor.getId(), revoke.reason(),
                revoke.idempotencyKey(), revokeHash(assignment.id(), revoke), now)) {
            throw versionConflict();
        }
        if (!repository.bumpGovernanceVersion(governanceVersion, now)) throw versionConflict();
        audit(assignment.expiresAt() == null ? "ADMIN_ROLE_REVOKED" : "ADMIN_ELEVATION_REVOKED",
                actor.getId(), assignment.userId(), null, "ADMIN_ROLE_ASSIGNMENT", assignment.id(),
                revoke.reason(), "REVOKED", Map.of("role", assignment.role(),
                        "approved", String.valueOf(approved)), correlationId, now);
        return assignment(repository.assignment(assignment.id(), false).orElseThrow(), now);
    }

    private GovernanceDomainExecutor.Execution executeRoleAction(User executor,
                                                                  GovernanceRepository.ApprovalRow row,
                                                                  String correlationId) {
        if ("ADMIN_ROLE_GRANT_SUPER_ADMIN".equals(row.actionType())) {
            String adminId = textPayload(row.payload(), "adminUserId");
            Instant effectiveAt = Instant.parse(textPayload(row.payload(), "effectiveAt"));
            String expiry = textPayload(row.payload(), "expiresAt");
            Instant expiresAt = expiry.isBlank() ? null : Instant.parse(expiry);
            long version = longPayload(row.payload(), "governanceVersion");
            ValidGrant grant = new ValidGrant("SUPER_ADMIN", effectiveAt, expiresAt,
                    textPayload(row.payload(), "reason"), version, "approved-" + row.id());
            RoleAssignment assignment = directGrant(executor, adminId, grant, correlationId, true);
            return GovernanceDomainExecutor.Execution.succeeded(assignment.assignmentId());
        }
        if ("ADMIN_ROLE_REVOKE_SUPER_ADMIN".equals(row.actionType())) {
            GovernanceRepository.AssignmentRow assignment = assignmentForAdmin(
                    textPayload(row.payload(), "adminUserId"), textPayload(row.payload(), "assignmentId"), false);
            ValidRevoke revoke = new ValidRevoke(longPayload(row.payload(), "assignmentVersion"),
                    textPayload(row.payload(), "reason"), "approved-" + row.id());
            RoleAssignment result = directRevoke(executor, assignment, revoke, correlationId, true);
            return GovernanceDomainExecutor.Execution.succeeded(result.assignmentId());
        }
        return GovernanceDomainExecutor.Execution.failed("GOVERNANCE_ACTION_UNSUPPORTED",
                "The approved role action is not supported.");
    }

    private GovernanceDomainExecutor.Validation revalidate(GovernanceRepository.ApprovalRow row, User executor) {
        if (row.expiresAt().compareTo(clock.instant()) <= 0) {
            return GovernanceDomainExecutor.Validation.invalid("GOVERNANCE_APPROVAL_EXPIRED",
                    "The approval request expired.");
        }
        SensitiveActionPolicy policy = policy(ActionType.valueOf(row.actionType()));
        if (!policy.enabled() || policy.version() != row.policyVersion()) {
            return GovernanceDomainExecutor.Validation.invalid("GOVERNANCE_POLICY_CHANGED",
                    "The governance policy changed after approval.");
        }
        if ("ADMIN_ROLE_GRANT_SUPER_ADMIN".equals(row.actionType())) {
            if (repository.governanceVersion(false) != longPayload(row.payload(), "governanceVersion")) {
                return GovernanceDomainExecutor.Validation.invalid("GOVERNANCE_ROLE_STATE_CHANGED",
                        "Admin authority changed after the role grant was requested.");
            }
            return GovernanceDomainExecutor.Validation.valid(canonicalJson(row.payload()));
        }
        if ("ADMIN_ROLE_REVOKE_SUPER_ADMIN".equals(row.actionType())) {
            GovernanceRepository.AssignmentRow assignment = repository.assignment(
                    textPayload(row.payload(), "assignmentId"), false).orElse(null);
            if (assignment == null || !"ACTIVE".equals(assignment.status())
                    || assignment.version() != longPayload(row.payload(), "assignmentVersion")
                    || !repository.permanentEffectiveSuperAdminExistsExcluding(
                    assignment.id(), clock.instant())) {
                return GovernanceDomainExecutor.Validation.invalid("GOVERNANCE_ROLE_STATE_CHANGED",
                        "The SUPER_ADMIN assignment or lockout protection changed after approval.");
            }
            return GovernanceDomainExecutor.Validation.valid(canonicalJson(row.payload()));
        }
        return domainExecutor.validate(row.actionType(), row.targetId(), row.payload(),
                currentActorProvider.currentActor().accessToken());
    }

    private RoleChangePreview grantPreview(User actor, String adminId, ValidGrant grant) {
        Instant now = clock.instant();
        List<String> current = displayRoles(repository.effectiveRoles(adminId, now));
        LinkedHashSet<String> proposedSet = new LinkedHashSet<>(current);
        if (!grant.effectiveAt().isAfter(now) && (grant.expiresAt() == null || grant.expiresAt().isAfter(now))) {
            proposedSet.add(grant.role());
        }
        List<String> proposed = List.copyOf(proposedSet);
        List<String> currentPermissions = repository.effectivePermissions(adminId, now);
        List<String> rolePermissions = rolePermissions(grant.role());
        List<String> added = rolePermissions.stream().filter(permission -> !currentPermissions.contains(permission)).toList();
        List<String> warnings = new ArrayList<>();
        if (grant.expiresAt() != null) warnings.add("Permissions disappear automatically when the assignment expires.");
        if ("SUPER_ADMIN".equals(grant.role())) warnings.add("SUPER_ADMIN grants require two independent approvals.");
        SensitiveActionPolicy policy = policy(ActionType.ADMIN_ROLE_GRANT_SUPER_ADMIN);
        return new RoleChangePreview(adminId, grant.role(), "GRANT", current, proposed, added, List.of(),
                grant.effectiveAt(), grant.expiresAt(), warnings, "SUPER_ADMIN".equals(grant.role()), false,
                "SUPER_ADMIN".equals(grant.role()) && policy.enabled(),
                "SUPER_ADMIN".equals(grant.role()) ? policy.requiredApprovals() : 0,
                grant.governanceVersion());
    }

    private RoleChangePreview revokePreview(User actor, GovernanceRepository.AssignmentRow assignment,
                                            RevokeRoleRequest request) {
        ValidRevoke revoke = validateRevoke(request);
        if (assignment.version() != revoke.expectedVersion()) throw versionConflict();
        Instant now = clock.instant();
        List<String> current = displayRoles(repository.effectiveRoles(assignment.userId(), now));
        List<String> proposed = new ArrayList<>(current);
        boolean anotherEffective = repository.assignments(assignment.userId(), false).stream()
                .anyMatch(value -> !value.id().equals(assignment.id()) && value.role().equals(assignment.role())
                        && effective(value, now));
        if (!anotherEffective) proposed.remove(assignment.role());
        List<String> beforePermissions = repository.effectivePermissions(assignment.userId(), now);
        List<String> removed = rolePermissions(assignment.role()).stream()
                .filter(beforePermissions::contains).toList();
        boolean last = "SUPER_ADMIN".equals(assignment.role()) && effective(assignment, now)
                && !repository.permanentEffectiveSuperAdminExistsExcluding(assignment.id(), now);
        if (last) throw GovernanceException.conflict("GOVERNANCE_LAST_SUPER_ADMIN",
                "The final active SUPER_ADMIN assignment cannot be revoked.");
        SensitiveActionPolicy policy = policy(ActionType.ADMIN_ROLE_REVOKE_SUPER_ADMIN);
        return new RoleChangePreview(assignment.userId(), assignment.role(), "REVOKE", current,
                proposed, List.of(), removed, assignment.effectiveAt(), assignment.expiresAt(),
                "SUPER_ADMIN".equals(assignment.role())
                        ? List.of("SUPER_ADMIN revocation requires two independent approvals.") : List.of(),
                "SUPER_ADMIN".equals(assignment.role()), last,
                "SUPER_ADMIN".equals(assignment.role()) && policy.enabled(),
                "SUPER_ADMIN".equals(assignment.role()) ? policy.requiredApprovals() : 0,
                repository.governanceVersion(false));
    }

    private ApprovalSummary requestApproval(User requester, ActionType actionType,
                                            String targetType, String targetId,
                                            Map<String, Object> payload, String summary,
                                            Long expectedTargetVersion, String idempotencyKey,
                                            String correlationId) {
        SensitiveActionPolicy policy = policy(actionType);
        requirePermissionId(requester, policy.requiredRequesterPermission());
        if (!policy.enabled() || policy.approvalMode() == ApprovalMode.NONE) {
            throw GovernanceException.conflict("GOVERNANCE_APPROVAL_NOT_REQUIRED",
                    "The selected action is not configured for approval.");
        }
        String fingerprint = hash(canonicalJson(payload));
        GovernanceRepository.ApprovalRow replay = repository.approvalRequest(requester.getId(),
                actionType.name(), idempotencyKey).orElse(null);
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint) || !replay.targetId().equals(targetId)) {
                throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was reused with changed approval content.");
            }
            return summary(replay);
        }
        Instant now = clock.instant();
        String approvalId = ids.next();
        GovernanceRepository.ApprovalInsert insert = new GovernanceRepository.ApprovalInsert(
                approvalId, actionType.name(), requester.getId(), targetType, targetId,
                fingerprint, requiredText(summary, "Safe action summary", 1000), payload,
                policy.riskLevel().name(), policy.requiredApprovals(), policy.version(),
                expectedTargetVersion, idempotencyKey, correlationId, now,
                now.plus(policy.approvalExpiresAfterMinutes(), ChronoUnit.MINUTES));
        if (!repository.insertApproval(insert)) {
            GovernanceRepository.ApprovalRow concurrentReplay = repository.approvalRequest(
                    requester.getId(), actionType.name(), idempotencyKey).orElseThrow();
            if (!concurrentReplay.fingerprint().equals(fingerprint)
                    || !concurrentReplay.targetId().equals(targetId)) {
                throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                        "The idempotency key was reused with changed approval content.");
            }
            return summary(concurrentReplay);
        }
        String requestedEvent = actionType == ActionType.ADMIN_ROLE_GRANT_SUPER_ADMIN
                ? "ADMIN_ROLE_GRANT_REQUESTED"
                : actionType == ActionType.ADMIN_ROLE_REVOKE_SUPER_ADMIN
                ? "ADMIN_ROLE_REVOKE_REQUESTED" : "SENSITIVE_ACTION_REQUESTED";
        audit(requestedEvent,
                requester.getId(), actionType.name().startsWith("ADMIN_ROLE_")
                        ? stringPayload(payload, "adminUserId") : null,
                approvalId, targetType, targetId, summary, "PENDING",
                Map.of("actionType", actionType.name(), "riskLevel", policy.riskLevel().name()),
                correlationId, now);
        return summary(repository.approval(approvalId, false).orElseThrow());
    }

    private ApprovalDetail detail(User actor, GovernanceRepository.ApprovalRow row) {
        ApprovalSummary summary = summary(row);
        SensitiveActionPolicy policy = policy(ActionType.valueOf(row.actionType()));
        boolean expired = row.expiresAt().compareTo(clock.instant()) <= 0;
        boolean terminal = Set.of("EXECUTED", "REJECTED", "CANCELLED", "INVALIDATED", "EXPIRED").contains(summary.status().name());
        boolean reviewer = has(actor, AdminPermission.GOVERNANCE_APPROVAL_REVIEW)
                && hasPermissionId(actor, policy.requiredApproverPermission());
        boolean selfProhibited = actor.getId().equals(row.requesterId()) && !policy.requesterMayApprove();
        boolean executable = "APPROVED".equals(row.status()) || "FAILED".equals(row.status());
        boolean executorPermission = hasPermissionId(actor, policy.requiredRequesterPermission());
        ApprovalCapabilities capabilities = new ApprovalCapabilities(
                reviewer && !selfProhibited && !expired && "PENDING".equals(row.status()),
                reviewer && !selfProhibited && !expired && "PENDING".equals(row.status()),
                executable && executorPermission && !expired,
                actor.getId().equals(row.requesterId()) && "PENDING".equals(row.status()) && !expired,
                selfProhibited, expired, terminal || expired,
                expired ? "The approval request has expired."
                        : terminal ? "The approval request is final and read-only."
                        : selfProhibited ? "Requesters cannot review their own sensitive actions." : null);
        List<ApprovalDecisionView> decisions = repository.decisions(row.id()).stream()
                .map(value -> new ApprovalDecisionView(value.id(), value.actorId(),
                        ApprovalDecision.valueOf(value.decision()), value.reason(), value.createdAt())).toList();
        return new ApprovalDetail(summary, row.summary(), row.payload(), policy, decisions,
                repository.eventsForApproval(row.id(), 100).stream().map(this::event).toList(),
                capabilities, row.executionReference(), row.failureCode(), row.failureSummary(),
                row.executorId(), row.executedAt());
    }

    private ApprovalSummary summary(GovernanceRepository.ApprovalRow row) {
        ApprovalStatus status = row.expiresAt().compareTo(clock.instant()) <= 0
                && ("PENDING".equals(row.status()) || "APPROVED".equals(row.status()))
                ? ApprovalStatus.EXPIRED : ApprovalStatus.valueOf(row.status());
        return new ApprovalSummary(row.id(), ActionType.valueOf(row.actionType()),
                RiskLevel.valueOf(row.riskLevel()), row.targetType(), row.targetId(),
                row.summary(), row.requesterId(), row.requiredApprovals(), row.approvalCount(),
                status, row.createdAt(), row.expiresAt(), row.version());
    }

    private SensitiveActionPolicy policy(ActionType actionType) {
        GovernanceRepository.PolicyRow value = repository.policy(actionType.name())
                .orElseThrow(() -> GovernanceException.notFound("Sensitive-action policy was not found."));
        return new SensitiveActionPolicy(actionType, RiskLevel.valueOf(value.riskLevel()),
                ApprovalMode.valueOf(value.approvalMode()), value.requiredApprovals(),
                value.requesterMayApprove(), value.expiresMinutes(), value.requesterPermission(),
                value.approverPermission(), value.threshold(), value.thresholdCurrency(),
                value.enabled(), value.version());
    }

    private GovernanceRepository.ApprovalRow requirePending(GovernanceRepository.ApprovalRow row,
                                                             long expectedVersion) {
        if (row.version() != expectedVersion) throw versionConflict();
        if (row.expiresAt().compareTo(clock.instant()) <= 0) {
            throw GovernanceException.conflict("GOVERNANCE_APPROVAL_EXPIRED",
                    "The approval request expired and cannot be reviewed.");
        }
        if (!"PENDING".equals(row.status())) {
            throw GovernanceException.conflict("GOVERNANCE_APPROVAL_FINAL",
                    "The approval request is no longer pending.");
        }
        return row;
    }

    private void requireExecutable(GovernanceRepository.ApprovalRow row, long expectedVersion, String key) {
        if (row.version() != expectedVersion) throw versionConflict();
        if (row.expiresAt().compareTo(clock.instant()) <= 0) {
            throw GovernanceException.conflict("GOVERNANCE_APPROVAL_EXPIRED",
                    "The approval request expired and cannot execute.");
        }
        if (!"APPROVED".equals(row.status()) && !"FAILED".equals(row.status())) {
            throw GovernanceException.conflict("GOVERNANCE_APPROVAL_NOT_EXECUTABLE",
                    "The required approvals have not been satisfied.");
        }
        if (row.executionIdempotencyKey() != null && !row.executionIdempotencyKey().equals(key)) {
            throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                    "Execution was already attempted with a different idempotency key.");
        }
    }

    private ValidGrant validateGrant(String adminId, GrantRoleRequest request) {
        if (request == null) throw GovernanceException.invalid("GOVERNANCE_ROLE_REQUEST_INVALID", "Role grant details are required.");
        String role = validRole(request.role());
        if (!ASSIGNABLE_ROLES.contains(role)) throw GovernanceException.invalid("GOVERNANCE_ROLE_NOT_ASSIGNABLE", "The role is not assignable.");
        Instant now = clock.instant();
        Instant effectiveAt = request.effectiveAt() == null ? now : request.effectiveAt();
        if (effectiveAt.isBefore(now.minus(1, ChronoUnit.MINUTES))) {
            throw GovernanceException.invalid("GOVERNANCE_ROLE_WINDOW_INVALID", "Effective time cannot be in the past.");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(effectiveAt)) {
            throw GovernanceException.invalid("GOVERNANCE_ROLE_WINDOW_INVALID", "Expiration must be after the effective time.");
        }
        if (request.expectedAdminGovernanceVersion() == null
                || request.expectedAdminGovernanceVersion() != repository.governanceVersion(false)) throw versionConflict();
        return new ValidGrant(role, effectiveAt, request.expiresAt(),
                requiredText(request.reason(), "Reason", 1000), request.expectedAdminGovernanceVersion(),
                key(request.idempotencyKey()));
    }

    /** Returns a semantically identical completed grant before current governance version checks. */
    private RoleAssignment replayedGrant(User actor, String adminId, GrantRoleRequest request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) return null;
        String idempotencyKey = key(request.idempotencyKey());
        GovernanceRepository.AssignmentRow replay = repository.assignmentRequest(actor.getId(), idempotencyKey)
                .orElse(null);
        if (replay == null) return null;
        String role = validRole(request.role());
        String reason = requiredText(request.reason(), "Reason", 1000);
        Instant requestedExpiry = request.expiresAt() == null ? null
                : request.expiresAt().truncatedTo(ChronoUnit.MICROS);
        Instant requestedEffective = request.effectiveAt() == null ? null
                : request.effectiveAt().truncatedTo(ChronoUnit.MICROS);
        boolean same = replay.userId().equals(adminId) && replay.role().equals(role)
                && replay.reason().equals(reason) && sameInstant(replay.expiresAt(), requestedExpiry)
                && (requestedEffective == null || sameInstant(replay.effectiveAt(), requestedEffective));
        if (!same) {
            throw GovernanceException.conflict("GOVERNANCE_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was reused with changed role assignment content.");
        }
        return assignment(replay, clock.instant());
    }

    private ValidRevoke validateRevoke(RevokeRoleRequest request) {
        if (request == null || request.expectedAssignmentVersion() == null) throw versionConflict();
        return new ValidRevoke(request.expectedAssignmentVersion(),
                requiredText(request.reason(), "Reason", 1000), key(request.idempotencyKey()));
    }

    private String revokeHash(String assignmentId, ValidRevoke revoke) {
        return hash(assignmentId + "|" + revoke.reason() + "|" + revoke.expectedVersion());
    }

    private ValidDecision validateDecision(DecisionRequest request) {
        if (request == null || request.expectedVersion() == null) throw versionConflict();
        return new ValidDecision(request.expectedVersion(), requiredText(request.reason(), "Reason", 1000),
                key(request.idempotencyKey()));
    }

    private GovernanceRepository.AssignmentRow assignmentForAdmin(String adminId, String rawAssignmentId,
                                                                   boolean lock) {
        GovernanceRepository.AssignmentRow assignment = repository.assignment(id(rawAssignmentId, "Assignment ID"), lock)
                .orElseThrow(() -> GovernanceException.notFound("Role assignment was not found."));
        if (!assignment.userId().equals(adminId)) throw GovernanceException.notFound("Role assignment was not found.");
        return assignment;
    }

    private String targetAdmin(String rawAdminId) {
        String adminId = id(rawAdminId, "Admin ID");
        GovernanceRepository.UserRow target = repository.user(adminId)
                .orElseThrow(() -> GovernanceException.notFound("Admin target was not found."));
        if (!"HUMAN".equals(target.accountType())) {
            throw GovernanceException.forbidden("GOVERNANCE_PROTECTED_IDENTITY",
                    "Protected service identities cannot receive human admin roles.");
        }
        return adminId;
    }

    private User roleManager(boolean elevation) {
        User actor = actor(AdminPermission.GOVERNANCE_ROLES_MANAGE);
        if (elevation) require(actor, AdminPermission.GOVERNANCE_ELEVATION_MANAGE);
        return actor;
    }

    private User actor(AdminPermission permission) {
        User user = authService.ensureUserEntity();
        require(user, permission);
        return user;
    }

    private void require(User user, AdminPermission permission) {
        authorization.requirePermission(user, permission);
    }

    private boolean has(User user, AdminPermission permission) {
        return authorization.accessFor(user).has(permission);
    }

    private boolean hasPermissionId(User user, String permission) {
        return authorization.accessFor(user).permissions().contains(permission);
    }

    private void requirePermissionId(User user, String permission) {
        if (!hasPermissionId(user, permission)) {
            throw GovernanceException.forbidden("GOVERNANCE_DOMAIN_PERMISSION_REQUIRED",
                    "The required domain permission is not currently effective.");
        }
    }

    private RoleAssignment assignment(GovernanceRepository.AssignmentRow value, Instant now) {
        AssignmentStatus status = "REVOKED".equals(value.status()) ? AssignmentStatus.REVOKED
                : value.effectiveAt().isAfter(now) ? AssignmentStatus.SCHEDULED
                : value.expiresAt() != null && !value.expiresAt().isAfter(now) ? AssignmentStatus.EXPIRED
                : AssignmentStatus.ACTIVE;
        return new RoleAssignment(value.id(), value.userId(), value.role(), status,
                value.effectiveAt(), value.expiresAt(), value.grantedBy(), value.reason(),
                value.createdAt(), value.revokedAt(), value.revokedBy(), value.revocationReason(), value.version());
    }

    private boolean effective(GovernanceRepository.AssignmentRow value, Instant now) {
        return "ACTIVE".equals(value.status()) && !value.effectiveAt().isAfter(now)
                && (value.expiresAt() == null || value.expiresAt().isAfter(now));
    }

    private List<String> displayRoles(List<String> roles) {
        LinkedHashSet<String> result = new LinkedHashSet<>(roles);
        if (result.remove("PLATFORM_ADMIN")) result.add("SUPER_ADMIN");
        return List.copyOf(result);
    }

    private List<String> rolePermissions(String role) {
        return repository.roles().stream().filter(value -> value.role().equals(role))
                .map(GovernanceRepository.RoleRow::permission).filter(Objects::nonNull).toList();
    }

    private String safeAdminLabel(String adminId) {
        return repository.user(adminId).map(GovernanceRepository.UserRow::displayName).orElse("Admin");
    }

    private void audit(String eventType, String actorId, String subjectId, String approvalId,
                       String targetType, String targetId, String reason, String outcome,
                       Map<String, String> metadata, String correlationId, Instant now) {
        repository.event(new GovernanceRepository.EventInsert(ids.next(), eventType, actorId,
                subjectId, approvalId, targetType, targetId, reason, outcome,
                metadata, correlationId, now));
    }

    private GovernanceEvent event(GovernanceRepository.EventRow value) {
        return new GovernanceEvent(value.id(), value.eventType(), value.actorId(), value.subjectId(),
                value.approvalId(), value.targetType(), value.targetId(), value.reason(),
                value.outcome(), value.metadata(), value.correlationId(), value.createdAt());
    }

    private String validRole(String raw) {
        String role = raw == null ? null : raw.trim().toUpperCase();
        if (role == null || !repository.roles().stream().map(GovernanceRepository.RoleRow::role).collect(Collectors.toSet()).contains(role)) {
            throw GovernanceException.invalid("GOVERNANCE_ROLE_INVALID", "Admin role is invalid.");
        }
        return role;
    }

    private String id(String value, String label) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || !ID.matcher(normalized).matches()) {
            throw GovernanceException.invalid("GOVERNANCE_ID_INVALID", label + " is invalid.");
        }
        return normalized;
    }

    private String key(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || !KEY.matcher(normalized).matches()) {
            throw GovernanceException.invalid("GOVERNANCE_IDEMPOTENCY_KEY_REQUIRED", "A valid idempotency key is required.");
        }
        return normalized;
    }

    private String requiredText(String value, String label, int max) {
        String normalized = optionalText(value, max);
        if (normalized == null) throw GovernanceException.invalid("GOVERNANCE_INPUT_INVALID", label + " is required.");
        return normalized;
    }

    private String optionalText(String value, int max) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) throw GovernanceException.invalid("GOVERNANCE_INPUT_INVALID", "Governance text is too long.");
        return normalized;
    }

    private <E extends Enum<E>> String enumFilter(String value, Class<E> type, String label) {
        if (value == null || value.isBlank()) return null;
        try { return Enum.valueOf(type, value.trim().toUpperCase()).name(); }
        catch (IllegalArgumentException exception) {
            throw GovernanceException.invalid("GOVERNANCE_FILTER_INVALID", label + " is invalid.");
        }
    }

    private String correlation(String value) {
        return value == null || value.isBlank() ? "governance-" + ids.next() : value;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Governance content could not be serialized."); }
    }

    /** Canonical ordering keeps approval fingerprints stable across JSON database round trips. */
    private String canonicalJson(Object value) {
        try {
            return mapper.writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Governance content could not be fingerprinted.");
        }
    }

    private String fingerprintMaterial(String value) {
        try {
            return hash(canonicalJson(mapper.readValue(value, Object.class)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Governance fingerprint material was invalid.");
        }
    }

    private boolean sameInstant(Instant left, Instant right) {
        if (left == null || right == null) return left == right;
        return Math.abs(left.toEpochMilli() - right.toEpochMilli()) < 1000;
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String textPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        String value = textPayload(payload, key);
        return value.isBlank() ? null : value;
    }

    private long longPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private GovernanceException versionConflict() {
        return GovernanceException.conflict("GOVERNANCE_VERSION_CONFLICT",
                "Governance state changed. Reload before continuing.");
    }

    private int pages(long total, int size) { return total == 0 ? 0 : (int) ((total + size - 1) / size); }

    private record ValidGrant(String role, Instant effectiveAt, Instant expiresAt,
                              String reason, long governanceVersion, String idempotencyKey) { }
    private record ValidRevoke(long expectedVersion, String reason, String idempotencyKey) { }
    private record ValidDecision(long expectedVersion, String reason, String key) { }
}
