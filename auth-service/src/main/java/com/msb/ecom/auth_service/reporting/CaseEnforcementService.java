package com.msb.ecom.auth_service.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts;
import com.msb.ecom.auth_service.dto.AdminUserContracts;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.*;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

import static com.msb.ecom.auth_service.reporting.InvestigationCaseContracts.*;

@Service
@RequiredArgsConstructor
public class CaseEnforcementService {
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Set<Scope> LISTING_SCOPES = Set.of(
            Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY);
    private final CaseEnforcementRepository proposals;
    private final InvestigationCaseRepository cases;
    private final AdminUserService users;
    private final AdminBusinessService businesses;
    private final CaseListingEnforcementClient listings;
    private final AuthService authService;
    private final AdminAuthorizationService authorization;
    private final UlidGenerator ulids;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    @Transactional
    public void create(String rawCaseId, CreateProposalRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null) throw invalid("CASE_PROPOSAL_INVALID", "Enforcement proposal details are required.");
        InvestigationCaseRepository.CaseRow caseRow = readyOwned(rawCaseId, request.expectedCaseVersion(), admin);
        Normalized normalized = normalize(request.targetType(), request.targetId(), request.actionType(), request.scopes(),
                request.reasonCode(), request.reason(), request.effectiveAt(), request.expiresAt(),
                request.expectedTargetVersion(), caseRow.id());
        Instant now = clock.instant();
        String proposalId = ulids.next();
        if (!cases.touchReadyOwned(caseRow.id(), caseRow.version(), admin.getId(), now))
            throw caseConflict();
        proposals.insert(row(proposalId, caseRow.id(), normalized, admin.getId(), correlationId, now));
        cases.insertEvent(event(caseRow.id(), "ENFORCEMENT_PROPOSAL_CREATED", admin, null, "DRAFT",
                normalized.reasonCode(), normalized.reason(), correlationId,
                Map.of("proposalId", proposalId, "targetType", normalized.targetType().name(),
                        "targetId", normalized.targetId())));
    }

    @Transactional
    public void update(String rawCaseId, String rawProposalId, UpdateProposalRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null) throw invalid("CASE_PROPOSAL_INVALID", "Enforcement proposal details are required.");
        InvestigationCaseRepository.CaseRow caseRow = readyOwned(rawCaseId, request.expectedCaseVersion(), admin);
        String proposalId = id("Proposal ID", rawProposalId);
        CaseEnforcementRepository.ProposalRow before = proposal(caseRow.id(), proposalId);
        requireProposalVersion(before, request.expectedProposalVersion());
        Normalized normalized = normalize(request.targetType(), request.targetId(), request.actionType(), request.scopes(),
                request.reasonCode(), request.reason(), request.effectiveAt(), request.expiresAt(),
                request.expectedTargetVersion(), caseRow.id());
        Instant now = clock.instant();
        if (!cases.touchReadyOwned(caseRow.id(), caseRow.version(), admin.getId(), now)) throw caseConflict();
        if (!proposals.update(row(proposalId, caseRow.id(), normalized, before.createdByAdminId(),
                before.correlationId(), before.createdAt()), before.version(), now)) throw proposalConflict();
        cases.insertEvent(event(caseRow.id(), "ENFORCEMENT_PROPOSAL_UPDATED", admin, before.status().name(),
                "DRAFT", normalized.reasonCode(), normalized.reason(), correlationId,
                Map.of("proposalId", proposalId)));
    }

    @Transactional
    public void dryRun(String rawCaseId, String rawProposalId, ProposalVersionRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        InvestigationCaseRepository.CaseRow caseRow = readyOwned(
                rawCaseId, request == null ? null : request.expectedCaseVersion(), admin);
        CaseEnforcementRepository.ProposalRow proposal = proposal(caseRow.id(), id("Proposal ID", rawProposalId));
        requireProposalVersion(proposal, request == null ? null : request.expectedProposalVersion());
        requireLinked(caseRow.id(), proposal.targetType(), proposal.targetId());
        requireTargetPermission(admin, proposal.targetType(), proposal.actionType());
        Dispatch dispatch = dispatch(proposal, caseRow.id(), null, true);
        Instant now = clock.instant();
        if (!proposals.validated(caseRow.id(), proposal.id(), proposal.version(), proposal.expectedTargetVersion(),
                dispatch.detail(), now)) throw proposalConflict();
    }

    @Transactional
    public void cancel(String rawCaseId, String rawProposalId, CancelProposalRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null) throw invalid("CASE_PROPOSAL_INVALID", "Cancellation details are required.");
        InvestigationCaseRepository.CaseRow caseRow = readyOwned(rawCaseId, request.expectedCaseVersion(), admin);
        CaseEnforcementRepository.ProposalRow proposal = proposal(caseRow.id(), id("Proposal ID", rawProposalId));
        requireProposalVersion(proposal, request.expectedProposalVersion());
        String reason = text(request.reason(), 1000, "Cancellation reason");
        Instant now = clock.instant();
        if (!cases.touchReadyOwned(caseRow.id(), caseRow.version(), admin.getId(), now)) throw caseConflict();
        if (!proposals.cancel(caseRow.id(), proposal.id(), proposal.version(), now)) throw proposalConflict();
        cases.insertEvent(event(caseRow.id(), "ENFORCEMENT_PROPOSAL_CANCELLED", admin, proposal.status().name(),
                "CANCELLED", "PROPOSAL_WAIVED", reason, correlationId, Map.of("proposalId", proposal.id())));
    }

    // Dispatch runs outside the Auth transaction so a Product success can be reconciled safely with the same key.
    public void execute(String rawCaseId, String rawProposalId, ExecuteProposalRequest request,
                        String correlationId) {
        Execution execution = transactions.execute(status -> prepare(rawCaseId, rawProposalId, request, correlationId));
        if (execution == null || execution.replay()) return;
        try {
            Dispatch result = dispatch(execution.proposal(), execution.caseId(), execution.key(), false);
            if (result.enforcementActionId() == null)
                throw new CaseEnforcementDispatchException("CASE_PROPOSAL_EXECUTION_FAILED", 502,
                        "The enforcement service did not return an action ID.");
            transactions.executeWithoutResult(status -> complete(execution, result.enforcementActionId(), correlationId));
        } catch (RuntimeException exception) {
            CaseEnforcementDispatchException failure = classify(exception);
            transactions.executeWithoutResult(status -> fail(execution, failure, correlationId));
            throw InvestigationCaseException.status(failure.code(), HttpStatus.valueOf(failure.status()),
                    failure.getMessage());
        }
    }

    @Transactional
    public void closeActioned(String rawCaseId, CloseActionedRequest request, String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        InvestigationCaseRepository.CaseRow row = readyOwned(rawCaseId,
                request == null ? null : request.expectedCaseVersion(), admin);
        String reason = text(request == null ? null : request.reason(), 1000, "Closure reason");
        if (proposals.executedCount(row.id()) < 1)
            throw conflict("CASE_ENFORCEMENT_REQUIRED", "At least one proposal must be executed before closure.");
        if (proposals.unresolvedCount(row.id()) > 0)
            throw conflict("CASE_HAS_UNRESOLVED_PROPOSALS", "Resolve every draft, validated, or failed proposal first.");
        Instant now = clock.instant();
        if (!cases.closeActioned(row.id(), row.version(), admin.getId(), reason, now)) throw caseConflict();
        cases.insertEvent(event(row.id(), "CASE_CLOSED_ACTIONED", admin, "READY_FOR_ACTION", "CLOSED_ACTIONED",
                "ACTION_COMPLETED", reason, correlationId, Map.of()));
    }

    @Transactional(readOnly = true)
    public Plan plan(InvestigationCaseRepository.CaseRow caseRow, User admin) {
        Map<String, String> labels = new HashMap<>();
        cases.targets(caseRow.id()).forEach(target -> labels.put(target.targetType() + ":" + target.targetId(),
                target.safeTargetLabel()));
        var access = authorization.accessFor(admin);
        boolean owner = admin.getId().equals(caseRow.assignedAdminId());
        boolean ready = caseRow.status() == Status.READY_FOR_ACTION;
        boolean canResolve = access.has(AdminPermission.REPORT_RESOLVE);
        List<Proposal> proposalViews = proposals.list(caseRow.id()).stream().map(value -> {
            boolean targetPermission = hasPermission(access, value.targetType(), value.actionType());
            boolean terminal = value.status() == ProposalStatus.EXECUTED || value.status() == ProposalStatus.CANCELLED;
            ProposalCapabilities capabilities = new ProposalCapabilities(
                    ready && owner && canResolve && !terminal, ready && owner && canResolve && !terminal,
                    ready && owner && canResolve && targetPermission && !terminal,
                    ready && owner && canResolve && targetPermission && value.status() == ProposalStatus.VALIDATED,
                    ready && owner && canResolve && targetPermission && value.status() == ProposalStatus.FAILED,
                    targetPermission);
            return new Proposal(value.id(), value.targetType(), value.targetId(),
                    labels.getOrDefault(value.targetType() + ":" + value.targetId(), "Linked target"),
                    value.actionType(), value.scopes(), value.reasonCode(), value.reason(), value.effectiveAt(),
                    value.expiresAt(), value.expectedTargetVersion(), value.status(), value.createdByAdminId(),
                    value.createdAt(), value.updatedAt(), value.version(), value.dryRunValidatedAt(),
                    value.dryRunTargetVersion(), value.dryRunResult(), value.resultingEnforcementActionId(),
                    value.executionErrorCode(), value.executionErrorSummary(), value.correlationId(), capabilities);
        }).toList();
        List<EnforcementLink> links = proposals.links(caseRow.id()).stream().map(value -> new EnforcementLink(
                value.proposalId(), value.targetType(), value.targetId(), value.enforcementActionId(),
                value.executedAt(), value.executedByAdminId(), value.correlationId())).toList();
        return new Plan(proposalViews, links);
    }

    private Execution prepare(String rawCaseId, String rawProposalId, ExecuteProposalRequest request,
                              String correlationId) {
        User admin = authorized(AdminPermission.REPORT_RESOLVE);
        if (request == null) throw invalid("CASE_PROPOSAL_INVALID", "Execution details are required.");
        InvestigationCaseRepository.CaseRow row = readyOwned(rawCaseId, request.expectedCaseVersion(), admin);
        CaseEnforcementRepository.ProposalRow proposal = proposal(row.id(), id("Proposal ID", rawProposalId));
        String key = text(request.idempotencyKey(), 128, "Execution idempotency key");
        if (proposal.status() == ProposalStatus.EXECUTED) {
            if (key.equals(proposal.executionIdempotencyKey())) return new Execution(row.id(), proposal, admin, key, true);
            throw conflict("CASE_PROPOSAL_ALREADY_EXECUTED", "The proposal was already executed.");
        }
        requireProposalVersion(proposal, request.expectedProposalVersion());
        if (proposal.status() != ProposalStatus.VALIDATED && proposal.status() != ProposalStatus.FAILED)
            throw conflict("CASE_PROPOSAL_NOT_VALIDATED", "Run a successful dry run before execution.");
        if (proposal.dryRunTargetVersion() == null
                || proposal.dryRunTargetVersion() != proposal.expectedTargetVersion())
            throw conflict("CASE_PROPOSAL_NOT_VALIDATED", "The proposal must be revalidated.");
        if (proposal.executionIdempotencyKey() != null && !key.equals(proposal.executionIdempotencyKey()))
            throw conflict("CASE_PROPOSAL_IDEMPOTENCY_CONFLICT",
                    "Retry this proposal with its original execution key.");
        requireLinked(row.id(), proposal.targetType(), proposal.targetId());
        requireTargetPermission(admin, proposal.targetType(), proposal.actionType());
        Instant now = clock.instant();
        if (!cases.touchReadyOwned(row.id(), row.version(), admin.getId(), now)) throw caseConflict();
        if (!proposals.pinExecution(row.id(), proposal.id(), proposal.version(), key, now)) throw proposalConflict();
        String eventType = proposal.status() == ProposalStatus.FAILED ? "ENFORCEMENT_RETRY" : "ENFORCEMENT_EXECUTION_STARTED";
        cases.insertEvent(event(row.id(), eventType, admin, proposal.status().name(), proposal.status().name(),
                proposal.reasonCode(), null, correlationId, Map.of("proposalId", proposal.id())));
        CaseEnforcementRepository.ProposalRow pinned = proposal(row.id(), proposal.id());
        return new Execution(row.id(), pinned, admin, key, false);
    }

    private void complete(Execution execution, String actionId, String correlationId) {
        Instant now = clock.instant();
        if (!proposals.executed(execution.caseId(), execution.proposal().id(), execution.proposal().version(),
                actionId, now)) throw proposalConflict();
        proposals.link(new CaseEnforcementRepository.LinkRow(execution.caseId(), execution.proposal().id(),
                execution.proposal().targetType(), execution.proposal().targetId(), actionId, now,
                execution.admin().getId(), correlationId));
        cases.touchReadyAfterExecution(execution.caseId(), now);
        cases.insertEvent(event(execution.caseId(), "ENFORCEMENT_EXECUTED", execution.admin(),
                execution.proposal().status().name(), "EXECUTED", execution.proposal().reasonCode(), null,
                correlationId, Map.of("proposalId", execution.proposal().id(), "enforcementActionId", actionId)));
    }

    private void fail(Execution execution, CaseEnforcementDispatchException failure, String correlationId) {
        Instant now = clock.instant();
        proposals.failed(execution.caseId(), execution.proposal().id(), execution.proposal().version(),
                failure.code(), safeSummary(failure.getMessage()), now);
        cases.touchReadyAfterExecution(execution.caseId(), now);
        cases.insertEvent(event(execution.caseId(), "ENFORCEMENT_EXECUTION_FAILED", execution.admin(),
                execution.proposal().status().name(), "FAILED", failure.code(), safeSummary(failure.getMessage()),
                correlationId, Map.of("proposalId", execution.proposal().id())));
    }

    private Dispatch dispatch(CaseEnforcementRepository.ProposalRow proposal, String caseId,
                              String key, boolean dryRun) {
        Map<String, String> metadata = Map.of("origin", "INVESTIGATION_CASE", "policyReference", proposal.id());
        try {
            return switch (proposal.targetType()) {
                case USER -> {
                    var request = new AdminUserContracts.CreateEnforcementRequest(proposal.actionType(),
                            proposal.scopes(), proposal.reasonCode(), proposal.reason(), proposal.effectiveAt(),
                            proposal.expiresAt(), proposal.expectedTargetVersion(),
                            dryRun ? "case-dry-run-" + proposal.id() : key, metadata);
                    if (dryRun) yield new Dispatch(null, mapper.valueToTree(
                            users.createCasePreview(proposal.targetId(), request, caseId)));
                    Result result = users.createCaseConfirmed(proposal.targetId(), request, caseId);
                    yield new Dispatch(result.enforcementActionId(), mapper.valueToTree(result));
                }
                case BUSINESS -> {
                    var request = new AdminBusinessContracts.CreateEnforcementRequest(proposal.actionType(),
                            proposal.scopes(), proposal.reasonCode(), proposal.reason(), proposal.effectiveAt(),
                            proposal.expiresAt(), proposal.expectedTargetVersion(),
                            dryRun ? "case-dry-run-" + proposal.id() : key, metadata);
                    if (dryRun) yield new Dispatch(null, mapper.valueToTree(
                            businesses.createCasePreview(proposal.targetId(), request, caseId)));
                    Result result = businesses.createCaseConfirmed(proposal.targetId(), request, caseId);
                    yield new Dispatch(result.enforcementActionId(), mapper.valueToTree(result));
                }
                case LISTING -> {
                    var command = new CaseListingEnforcementClient.Command(caseId, proposal.id(),
                            proposal.targetId(), proposal.actionType(), proposal.scopes(), proposal.reasonCode(),
                            proposal.reason(), proposal.effectiveAt(), proposal.expiresAt(),
                            proposal.expectedTargetVersion(), key);
                    var result = dryRun ? listings.dryRun(command) : listings.execute(command);
                    yield new Dispatch(result.enforcementActionId(), result.detail());
                }
                default -> throw invalid("CASE_PROPOSAL_TARGET_INVALID", "Unsupported proposal target.");
            };
        } catch (CaseEnforcementDispatchException exception) {
            throw exception;
        } catch (EnforcementExceptions.Conflict exception) {
            throw new CaseEnforcementDispatchException("CASE_TARGET_VERSION_CONFLICT", 409,
                    safeSummary(exception.getMessage()));
        } catch (EnforcementExceptions.NotFound exception) {
            throw new CaseEnforcementDispatchException("CASE_TARGET_NOT_FOUND", 404,
                    safeSummary(exception.getMessage()));
        } catch (EnforcementExceptions.Validation exception) {
            throw new CaseEnforcementDispatchException("CASE_PROPOSAL_VALIDATION_FAILED", 400,
                    safeSummary(exception.getMessage()));
        } catch (BusinessApplicationForbiddenException exception) {
            throw new CaseEnforcementDispatchException("CASE_ENFORCEMENT_PERMISSION_DENIED", 403,
                    "The required target enforcement permission is missing.");
        }
    }

    private Normalized normalize(ReportContracts.TargetType targetType, String rawTargetId, ActionType action,
                                 Set<Scope> rawScopes, String rawReasonCode, String rawReason,
                                 Instant effectiveAt, Instant expiresAt, Long targetVersion, String caseId) {
        ReportContracts.TargetType type = targetType(targetType);
        String targetId = id("Target ID", rawTargetId);
        if (!cases.hasTarget(caseId, type, targetId))
            throw conflict("CASE_TARGET_NOT_LINKED", "Enforcement proposals may target only linked case targets.");
        if (action == null) throw invalid("CASE_PROPOSAL_ACTION_REQUIRED", "Action type is required.");
        Set<Scope> operational = switch (type) {
            case USER -> AdminUserService.OPERATIONAL_SCOPES;
            case BUSINESS -> AdminBusinessService.OPERATIONAL_SCOPES;
            case LISTING -> LISTING_SCOPES;
            default -> Set.of();
        };
        if (type == ReportContracts.TargetType.LISTING && action == ActionType.BAN)
            throw invalid("CASE_PROPOSAL_ACTION_INVALID", "Listing enforcement does not support BAN.");
        Set<Scope> scopes = action == ActionType.BAN ? operational : rawScopes;
        if (scopes == null || scopes.isEmpty() || !operational.containsAll(scopes))
            throw invalid("CASE_PROPOSAL_SCOPES_INVALID", "Select operational scopes for the target.");
        String reasonCode = text(rawReasonCode, 64, "Reason code");
        if (!REASON_CODE.matcher(reasonCode).matches())
            throw invalid("CASE_PROPOSAL_REASON_CODE_INVALID", "Reason code must use uppercase identifiers.");
        String reason = text(rawReason, 1000, "Reason");
        long version = version(targetVersion, "target");
        return new Normalized(type, targetId, action, Set.copyOf(scopes), reasonCode, reason,
                effectiveAt, expiresAt, version);
    }

    private CaseEnforcementRepository.ProposalRow row(String id, String caseId, Normalized value,
                                                       String adminId, String correlationId, Instant now) {
        return new CaseEnforcementRepository.ProposalRow(id, caseId, value.targetType(), value.targetId(),
                value.actionType(), value.scopes(), value.reasonCode(), value.reason(), value.effectiveAt(),
                value.expiresAt(), value.expectedTargetVersion(), ProposalStatus.DRAFT, adminId, now, now, 0,
                null, null, null, null, null, null, null, correlationId);
    }

    private InvestigationCaseRepository.CaseRow readyOwned(String rawCaseId, Long expected, User admin) {
        InvestigationCaseRepository.CaseRow row = ready(rawCaseId, expected);
        if (!admin.getId().equals(row.assignedAdminId()))
            throw conflict(row.assignedAdminId() == null ? "INVESTIGATION_CASE_NOT_ASSIGNED"
                    : "INVESTIGATION_CASE_ASSIGNED_TO_OTHER", "Only the assigned investigator may edit this plan.");
        return row;
    }

    private InvestigationCaseRepository.CaseRow ready(String rawCaseId, Long expected) {
        String caseId = id("Case ID", rawCaseId);
        InvestigationCaseRepository.CaseRow row = cases.find(caseId).orElseThrow(InvestigationCaseException::notFound);
        if (row.version() != version(expected, "case")) throw caseConflict();
        if (row.status() != Status.READY_FOR_ACTION)
            throw conflict("CASE_NOT_READY_FOR_ACTION", "Only a case ready for action can use an enforcement plan.");
        return row;
    }

    private void requireLinked(String caseId, ReportContracts.TargetType type, String targetId) {
        if (!cases.hasTarget(caseId, type, targetId))
            throw conflict("CASE_TARGET_NOT_LINKED", "The proposal target is no longer linked to the case.");
    }

    private void requireTargetPermission(User admin, ReportContracts.TargetType type, ActionType action) {
        if (!hasPermission(authorization.accessFor(admin), type, action))
            throw InvestigationCaseException.forbidden("CASE_ENFORCEMENT_PERMISSION_DENIED",
                    "The required target enforcement permission is missing.");
    }

    private boolean hasPermission(AdminAuthorizationService.AdminAccessSnapshot access,
                                  ReportContracts.TargetType type, ActionType action) {
        return access.has(requiredPermission(type, action));
    }

    private AdminPermission requiredPermission(ReportContracts.TargetType type, ActionType action) {
        return switch (type) {
            case USER -> switch (action) {
                case RESTRICT -> AdminPermission.USER_RESTRICT;
                case SUSPEND -> AdminPermission.USER_SUSPEND;
                case BAN -> AdminPermission.USER_BAN;
            };
            case BUSINESS -> switch (action) {
                case RESTRICT -> AdminPermission.BUSINESS_RESTRICT;
                case SUSPEND -> AdminPermission.BUSINESS_SUSPEND;
                case BAN -> AdminPermission.BUSINESS_BAN;
            };
            case LISTING -> AdminPermission.LISTING_SUSPEND;
            default -> throw invalid("CASE_PROPOSAL_TARGET_INVALID", "Unsupported proposal target.");
        };
    }

    private User authorized(AdminPermission permission) {
        User admin = authService.ensureUserEntity(); authorization.requirePermission(admin, permission); return admin;
    }

    private CaseEnforcementRepository.ProposalRow proposal(String caseId, String proposalId) {
        return proposals.find(caseId, proposalId).orElseThrow(() ->
                InvestigationCaseException.status("CASE_PROPOSAL_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Enforcement proposal was not found."));
    }

    private void requireProposalVersion(CaseEnforcementRepository.ProposalRow proposal, Long expected) {
        if (proposal.version() != version(expected, "proposal")) throw proposalConflict();
    }

    private InvestigationCaseRepository.EventRow event(String caseId, String type, User actor,
            String previous, String next, String reasonCode, String reason, String correlationId,
            Map<String, String> metadata) {
        return new InvestigationCaseRepository.EventRow(ulids.next(), caseId, type, clock.instant(), actor.getId(),
                safeName(actor), previous, next, reasonCode, reason, correlationId, ulids.next(), metadata);
    }

    private CaseEnforcementDispatchException classify(RuntimeException exception) {
        if (exception instanceof CaseEnforcementDispatchException known) return known;
        return new CaseEnforcementDispatchException("CASE_PROPOSAL_EXECUTION_UNCERTAIN", 503,
                "The enforcement outcome could not be confirmed. Retry with the same execution key.");
    }

    private String id(String name, String value) {
        try { return FixedLengthIds.requireTrimmed(name, value, 26); }
        catch (IllegalArgumentException exception) { throw invalid("INVESTIGATION_ID_INVALID", exception.getMessage()); }
    }
    private long version(Long value, String name) {
        if (value == null || value < 0) throw invalid("INVESTIGATION_VERSION_REQUIRED",
                "The current " + name + " version is required.");
        return value;
    }
    private String text(String value, int max, String name) {
        if (value == null || value.isBlank()) throw invalid("INVESTIGATION_TEXT_REQUIRED", name + " is required.");
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
            throw invalid("INVESTIGATION_TEXT_INVALID", name + " is invalid.");
        return normalized;
    }
    private ReportContracts.TargetType targetType(ReportContracts.TargetType type) {
        if (type == null || !Set.of(ReportContracts.TargetType.USER, ReportContracts.TargetType.BUSINESS,
                ReportContracts.TargetType.LISTING).contains(type))
            throw invalid("CASE_PROPOSAL_TARGET_INVALID", "Only user, business, and listing targets are supported.");
        return type;
    }
    private InvestigationCaseException caseConflict() {
        return conflict("INVESTIGATION_CASE_VERSION_CONFLICT", "The case changed. Refresh and try again.");
    }
    private InvestigationCaseException proposalConflict() {
        return conflict("CASE_PROPOSAL_VERSION_CONFLICT", "The proposal changed. Refresh and try again.");
    }
    private static InvestigationCaseException invalid(String code, String message) {
        return InvestigationCaseException.invalid(code, message);
    }
    private static InvestigationCaseException conflict(String code, String message) {
        return InvestigationCaseException.conflict(code, message);
    }
    private static String safeName(User admin) {
        return admin.getDisplayName() == null || admin.getDisplayName().isBlank()
                ? "Platform admin" : admin.getDisplayName().trim();
    }
    private static String safeSummary(String value) {
        if (value == null || value.isBlank()) return "Enforcement validation failed.";
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    public record Plan(List<Proposal> proposals, List<EnforcementLink> links) { }
    private record Normalized(ReportContracts.TargetType targetType, String targetId, ActionType actionType,
                              Set<Scope> scopes, String reasonCode, String reason, Instant effectiveAt,
                              Instant expiresAt, long expectedTargetVersion) { }
    private record Dispatch(String enforcementActionId, JsonNode detail) { }
    private record Execution(String caseId, CaseEnforcementRepository.ProposalRow proposal, User admin,
                             String key, boolean replay) { }
}
