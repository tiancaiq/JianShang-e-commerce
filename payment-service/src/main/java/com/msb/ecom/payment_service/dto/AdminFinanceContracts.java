package com.msb.ecom.payment_service.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AdminFinanceContracts {
    private AdminFinanceContracts() { }
    public record Page<T>(List<T> content,int page,int size,long totalElements,int totalPages,String sort) { public Page { content=List.copyOf(content); } }
    public record PaymentSummary(String paymentId,String checkoutId,String orderId,String buyerUserId,String buyerDisplayName,
            List<String> businessIds,List<String> businessDisplayNames,String provider,String safeProviderPaymentReference,
            String paymentStatus,BigDecimal amount,BigDecimal capturedAmount,BigDecimal refundedAmount,
            BigDecimal pendingRefundAmount,BigDecimal refundableAmount,String currency,int refundCount,
            String reconciliationState,Instant createdAt,Instant updatedAt,long version) { }
    public record PaymentDetail(PaymentSummary payment,OrderContext orderContext,List<RefundSummary> refunds,
            List<DisputeContext> disputeRecommendations,List<TimelineEntry> timeline,Capabilities capabilities) { }
    public record OrderContext(String orderId,String orderNumber,BigDecimal totalAmount,String currency,
            List<BusinessContext> businesses,List<ItemContext> purchaseItems,String adminPath,boolean available) { }
    public record BusinessContext(String businessId,String displayName,String storeNameAtPurchase,BigDecimal totalAmount,String adminPath) { }
    public record ItemContext(String listingId,String titleAtPurchase,int quantity,BigDecimal lineTotal) { }
    public record DisputeContext(String disputeId,String businessId,String resolutionType,BigDecimal recommendedRefundAmount,
            String currency,String resolutionReason,Instant resolvedAt,String adminPath,boolean financiallyExecutable) { }
    public record RefundSummary(String refundId,String paymentId,String orderId,String disputeId,BigDecimal amount,String currency,
            String status,String provider,String safeProviderRefundReference,String source,String initiatedByType,
            String initiatedByAdminId,Instant createdAt,Instant updatedAt,Instant completedAt,long version) { }
    public record RefundDetail(RefundSummary refund,BigDecimal originalPaymentAmount,BigDecimal capturedAmount,
            BigDecimal refundedBeforeOperation,BigDecimal refundedTotal,BigDecimal currentlyRefundableAmount,
            int attemptCount,RefundAttempt latestAttempt,String reconciliationState,String safeFailureCode,
            String safeFailureSummary,List<TimelineEntry> timeline,Capabilities capabilities) { }
    public record RefundAttempt(int attemptNumber,String operation,String outcome,String safeProviderReference,
            String safeFailureCode,String safeFailureSummary,Instant createdAt) { }
    public record Capabilities(boolean canReadFinance,boolean canReadRefund,boolean canExecuteRefund,
            boolean canRetryRefund,boolean canViewPii,boolean isRefundable,boolean isRetryable,
            boolean requiresDryRun,boolean isReadOnly,String readOnlyReason) { }
    public record TimelineEntry(String eventId,Instant occurredAt,String eventType,String actorType,String actorId,
            String actorDisplayName,String previousState,String newState,String reasonCode,String reason,
            String correlationId,Map<String,Object> safeMetadata) { }
    public record RefundRequest(String refundType,BigDecimal amount,String currency,String reasonCode,String reason,
            String disputeId,Long expectedPaymentVersion,String idempotencyKey) { }
    public record RefundPreview(String paymentId,String orderId,String disputeId,String refundType,BigDecimal requestedAmount,
            String currency,BigDecimal capturedAmount,BigDecimal alreadyRefundedAmount,BigDecimal pendingRefundAmount,
            BigDecimal currentlyRefundableAmount,BigDecimal projectedRefundedTotal,
            BigDecimal projectedRemainingRefundableAmount,String paymentStatus,String recommendationComparison,
            List<String> warnings,boolean allowed,String denialReason,long expectedPaymentVersion) { }
    public record RefundExecution(String refundId,String paymentId,String orderId,String disputeId,BigDecimal amount,
            String currency,String status,String reconciliationState,long paymentVersion,long refundVersion,
            boolean replayed,Instant createdAt,Instant completedAt) { }
    public record ApprovalReference(String approvalId,String actionType,String riskLevel,String targetType,
            String targetId,int requiredApprovals,int currentApprovals,String status,Instant createdAt,
            Instant expiresAt,long version) { }
    public record RefundApproval(String outcome,ApprovalReference approval,boolean approvalRequired,String message) { }
    public record GovernedRefundResult(boolean approvalRequired,RefundExecution execution,
            RefundApproval approval) { }
}
