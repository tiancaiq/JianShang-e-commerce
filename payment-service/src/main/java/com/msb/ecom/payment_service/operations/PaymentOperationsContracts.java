package com.msb.ecom.payment_service.operations;
import java.time.Instant;import java.util.List;
public final class PaymentOperationsContracts {private PaymentOperationsContracts(){}
 public record Outbox(String eventId,String ownerService,String eventType,String aggregateType,String aggregateId,String status,int attemptCount,Instant createdAt,Instant lastAttemptAt,Instant nextAttemptAt,String correlationId,String safeFailureCode,String safeFailureSummary,boolean retryable){}
 public record Reconciliation(String issueId,String issueType,String ownerService,String targetType,String targetId,String localState,String externalStateSafe,String severity,String status,Instant lastCheckedAt,int attemptCount,String safeSummary,boolean retryable,String correlationId){}
 public record Feature(String featureKey,String displayName,String status,String scope,String source,Instant lastChangedAt,boolean mutable,String safeSummary){}
 public record Snapshot(String ownerService,boolean available,String safeSummary,List<Object> jobs,List<Outbox> outbox,List<Reconciliation> reconciliation,List<Object> inventory,Object search,List<Feature> features){}
 public record ActionRequest(String targetType,String targetId,String commandType,boolean dryRun,String reason,String correlationId){}
 public record Action(String ownerService,String targetType,String targetId,String commandType,String currentState,boolean allowed,String denialReason,List<String> knownDependencies,List<String> warnings,String expectedOperation,String customerImpact,String result,String safeSummary){}
}
