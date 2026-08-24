package com.msb.ecom.auth_service.governance;

import com.msb.ecom.auth_service.governance.GovernanceContracts.*;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/governance")
@RequiredArgsConstructor
public class AdminGovernanceController {
    private final AdminGovernanceService service;

    @GetMapping
    ResponseEntity<GovernanceDashboard> dashboard() { return ok(service.dashboard()); }

    @GetMapping("/admins")
    ResponseEntity<Page<AdminSummary>> admins(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String role,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) Boolean hasTemporaryElevation,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "25") int size,
                                               @RequestParam(defaultValue = "name,asc") String sort) {
        return ok(service.admins(q, role, status, hasTemporaryElevation, page, size, sort));
    }

    @GetMapping("/admins/{adminId}")
    ResponseEntity<AdminDetail> admin(@PathVariable String adminId) { return ok(service.admin(adminId)); }

    @GetMapping("/roles")
    ResponseEntity<List<RoleDefinition>> roles() { return ok(service.roles()); }

    @PostMapping("/admins/{adminId}/roles/dry-run")
    ResponseEntity<RoleChangePreview> previewGrant(@PathVariable String adminId,
                                                   @RequestBody GrantRoleRequest request) {
        return ok(service.previewGrant(adminId, request));
    }

    @PostMapping("/admins/{adminId}/roles")
    ResponseEntity<RoleChangeResult> grant(@PathVariable String adminId,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @RequestBody GrantRoleRequest request,
                                           HttpServletRequest servlet) {
        GrantRoleRequest body = new GrantRoleRequest(request.role(), request.effectiveAt(), request.expiresAt(),
                request.reason(), request.expectedAdminGovernanceVersion(),
                key == null ? request.idempotencyKey() : key);
        RoleChangeResult result = service.grant(adminId, body, CorrelationIdFilter.current(servlet));
        return "PENDING_APPROVAL".equals(result.outcome()) ? accepted(result) : created(result);
    }

    @PostMapping("/admins/{adminId}/roles/{assignmentId}/revoke/dry-run")
    ResponseEntity<RoleChangePreview> previewRevoke(@PathVariable String adminId,
                                                    @PathVariable String assignmentId,
                                                    @RequestBody RevokeRoleRequest request) {
        return ok(service.previewRevoke(adminId, assignmentId, request));
    }

    @PostMapping("/admins/{adminId}/roles/{assignmentId}/revoke")
    ResponseEntity<RoleChangeResult> revoke(@PathVariable String adminId,
                                            @PathVariable String assignmentId,
                                            @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                            @RequestBody RevokeRoleRequest request,
                                            HttpServletRequest servlet) {
        RevokeRoleRequest body = new RevokeRoleRequest(request.expectedAssignmentVersion(), request.reason(),
                key == null ? request.idempotencyKey() : key);
        RoleChangeResult result = service.revoke(adminId, assignmentId, body,
                CorrelationIdFilter.current(servlet));
        return "PENDING_APPROVAL".equals(result.outcome()) ? accepted(result) : ok(result);
    }

    @GetMapping("/approvals")
    ResponseEntity<Page<ApprovalSummary>> approvals(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String riskLevel,
                                                    @RequestParam(required = false) String actionType,
                                                    @RequestParam(required = false) String requesterAdminId,
                                                    @RequestParam(required = false) String targetType,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "25") int size,
                                                    @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return ok(service.approvals(status, riskLevel, actionType, requesterAdminId,
                targetType, page, size, sort));
    }

    @GetMapping("/approvals/{approvalId}")
    ResponseEntity<ApprovalDetail> approval(@PathVariable String approvalId) {
        return ok(service.approval(approvalId));
    }

    @PostMapping("/approvals/{approvalId}/approve")
    ResponseEntity<ApprovalDetail> approve(@PathVariable String approvalId,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @RequestBody DecisionRequest request,
                                           HttpServletRequest servlet) {
        DecisionRequest body = new DecisionRequest(request.expectedVersion(), request.reason(),
                key == null ? request.idempotencyKey() : key);
        return ok(service.decide(approvalId, ApprovalDecision.APPROVE, body,
                CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/approvals/{approvalId}/reject")
    ResponseEntity<ApprovalDetail> reject(@PathVariable String approvalId,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                          @RequestBody DecisionRequest request,
                                          HttpServletRequest servlet) {
        DecisionRequest body = new DecisionRequest(request.expectedVersion(), request.reason(),
                key == null ? request.idempotencyKey() : key);
        return ok(service.decide(approvalId, ApprovalDecision.REJECT, body,
                CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/approvals/{approvalId}/cancel")
    ResponseEntity<ApprovalDetail> cancel(@PathVariable String approvalId,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                          @RequestBody CancelRequest request,
                                          HttpServletRequest servlet) {
        CancelRequest body = new CancelRequest(request.expectedVersion(), request.reason(),
                key == null ? request.idempotencyKey() : key);
        return ok(service.cancel(approvalId, body, CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/approvals/{approvalId}/execute")
    ResponseEntity<ApprovalDetail> execute(@PathVariable String approvalId,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @RequestBody ExecuteRequest request,
                                           HttpServletRequest servlet) {
        ExecuteRequest body = new ExecuteRequest(request.expectedApprovalVersion(),
                key == null ? request.idempotencyKey() : key);
        return accepted(service.execute(approvalId, body, CorrelationIdFilter.current(servlet)));
    }

    @PostMapping("/domain/refunds")
    ResponseEntity<DomainApprovalResult> refund(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                @RequestBody RefundGovernanceRequest request,
                                                HttpServletRequest servlet) {
        RefundGovernanceRequest body = new RefundGovernanceRequest(request.paymentId(), request.refundType(),
                request.amount(), request.currency(), request.reasonCode(), request.reason(), request.disputeId(),
                request.expectedPaymentVersion(), key == null ? request.idempotencyKey() : key,
                request.safeSummary());
        DomainApprovalResult result = service.evaluateRefund(body, CorrelationIdFilter.current(servlet));
        return result.approvalRequired() ? accepted(result) : ok(result);
    }

    @PostMapping("/domain/catalog-category-disable")
    ResponseEntity<DomainApprovalResult> catalog(@RequestHeader(name = "Idempotency-Key", required = false) String key,
                                                 @RequestBody CatalogGovernanceRequest request,
                                                 HttpServletRequest servlet) {
        CatalogGovernanceRequest body = new CatalogGovernanceRequest(request.categoryId(), request.proposedStatus(),
                request.activeListingCount(), request.draftListingCount(), request.pendingListingCount(),
                request.expectedCategoryVersion(), request.reason(),
                key == null ? request.idempotencyKey() : key, request.safeCategoryLabel());
        DomainApprovalResult result = service.evaluateCatalogDisable(body, CorrelationIdFilter.current(servlet));
        return result.approvalRequired() ? accepted(result) : ok(result);
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    private <T> ResponseEntity<T> accepted(T body) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(body);
    }
}
