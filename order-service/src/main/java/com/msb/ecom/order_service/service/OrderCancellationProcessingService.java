package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderCancellationProcessingProperties;
import com.msb.ecom.order_service.repository.OrderCancellationProcessingRepository;
import com.msb.ecom.order_service.repository.OrderCancellationProcessingRepository.CompensationRecord;
import com.msb.ecom.order_service.repository.OrderCancellationProcessingRepository.DecisionRecord;
import com.msb.ecom.order_service.repository.OrderCancellationProcessingRepository.GroupRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderCancellationProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationProcessingService.class);
    private final OrderCancellationProcessingProperties properties;
    private final OrderCancellationProcessingRepository repository;
    private final CancellationInventoryClient inventory;
    private final CancellationPaymentClient payments;
    private final CheckoutUlidGenerator ids;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OrderCancellationProcessingService(
            OrderCancellationProcessingProperties properties,
            OrderCancellationProcessingRepository repository,
            CancellationInventoryClient inventory,
            CancellationPaymentClient payments,
            CheckoutUlidGenerator ids,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.repository = repository;
        this.inventory = inventory;
        this.payments = payments;
        this.ids = ids;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = Clock.systemUTC();
    }

    public boolean enabled() {
        return properties.enabled();
    }

    // Auto-decides only the buyer-requested, all-pending-acceptance state locked by ORD-03A.
    public void decidePending() {
        for (String requestId : repository.pendingRequestIds(properties.batchSize())) {
            try {
                transactions.executeWithoutResult(status -> decide(requestId, clock.instant()));
            } catch (RuntimeException exception) {
                log.warn("Cancellation auto-decision deferred result=failed exceptionType={}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private void decide(String requestId, Instant now) {
        DecisionRecord record = repository.lockDecision(requestId).orElse(null);
        if (record == null) {
            return;
        }
        if (record.reservationId() == null || record.paymentIntentId() == null) {
            throw new IllegalStateException("Cancellation is missing checkout compensation evidence.");
        }
        List<GroupRecord> groups = repository.lockGroups(record.orderId());
        if (groups.isEmpty() || groups.stream().anyMatch(group ->
                !"PENDING_ACCEPTANCE".equals(group.fulfillmentStatus())
                        || !"CANCELLATION_PENDING".equals(group.cancellationStatus()))) {
            throw new IllegalStateException("Cancellation decision no longer has an eligible group set.");
        }
        String causationId = ids.next();
        if (repository.cancelOrder(record, now) != 1) {
            throw new IllegalStateException("Cancellation decision lost the order version.");
        }
        for (GroupRecord group : groups) {
            if (repository.cancelGroup(group.businessOrderId(), now) != 1) {
                throw new IllegalStateException("Cancellation decision lost a fulfillment group.");
            }
            repository.insertGroupHistory(ids.next(), record, group, causationId, now);
        }
        if (repository.approveRequest(requestId, now) != 1) {
            throw new IllegalStateException("Cancellation decision lost the request.");
        }
        repository.insertOrderHistory(ids.next(), record, causationId, now);
        repository.insertCompensation(ids.next(), record, now);
        repository.insertOutbox(ids.next(), record.orderId(),
                "order.cancellation_auto_approved",
                event("order.cancellation_auto_approved", record, groups, now),
                record.correlationId(), causationId, now);
        log.info("Order cancellation auto-approved result=approved groupCount={}", groups.size());
    }

    // Drives two independently idempotent compensations and records truthful partial progress.
    public void processCompensations() {
        Instant now = clock.instant();
        for (CompensationRecord initial : repository.dueCompensations(now, properties.batchSize())) {
            processOne(initial);
        }
    }

    private void processOne(CompensationRecord initial) {
        try {
            CompensationRecord current = repository.findCompensation(initial.id()).orElse(null);
            if (current == null) {
                return;
            }
            if (!"SUCCEEDED".equals(current.inventoryStatus())) {
                inventory.restock(current.reservationId(), current.orderId(), current.requestId(),
                        "cancel-inventory:" + current.requestId());
                Instant succeededAt = clock.instant();
                String compensationId = current.id();
                transactions.executeWithoutResult(status -> repository.inventorySucceeded(
                        compensationId, succeededAt));
            }
            current = repository.findCompensation(initial.id()).orElseThrow();
            if (!"SUCCEEDED".equals(current.refundStatus())) {
                var refund = payments.refund(
                        current.paymentIntentId(), current.orderId(), current.requestId(),
                        "cancel-refund:" + current.requestId());
                Instant succeededAt = clock.instant();
                CompensationRecord captured = current;
                transactions.executeWithoutResult(status -> repository.refundSucceeded(
                        captured.id(), refund.refundId(), refund.providerReference(), succeededAt));
            }
            complete(initial.id());
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            repository.retryLater(initial.id(), "DEPENDENCY_UNAVAILABLE",
                    failedAt.plus(properties.retryDelay()), failedAt);
            log.warn("Order cancellation compensation deferred retryCount={} exceptionType={}",
                    initial.retryCount() + 1, exception.getClass().getSimpleName());
        }
    }

    private void complete(String id) {
        Instant now = clock.instant();
        transactions.executeWithoutResult(status -> {
            CompensationRecord record = repository.findCompensation(id).orElseThrow();
            if (!"SUCCEEDED".equals(record.inventoryStatus())
                    || !"SUCCEEDED".equals(record.refundStatus())) {
                return;
            }
            if (repository.complete(record.id(), now) != 1) {
                return;
            }
            if (repository.completeRequest(record.requestId(), now) != 1) {
                throw new IllegalStateException("Cancellation request completion was lost.");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "order.cancellation_completed");
            payload.put("orderId", record.orderId());
            payload.put("cancellationRequestId", record.requestId());
            payload.put("refundId", record.refundId());
            payload.put("amount", record.amount());
            payload.put("currency", record.currency());
            payload.put("occurredAt", now);
            repository.insertOutbox(ids.next(), record.orderId(),
                    "order.cancellation_completed", json(payload), ids.next(),
                    record.requestId(), now);
        });
        log.info("Order cancellation compensation completed result=completed");
    }

    private String event(
            String type, DecisionRecord record, List<GroupRecord> groups, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", type);
        payload.put("orderId", record.orderId());
        payload.put("cancellationRequestId", record.requestId());
        payload.put("orderStatus", "CANCELLED");
        payload.put("orderVersion", record.orderVersion() + 1);
        payload.put("businessOrderIds", groups.stream().map(GroupRecord::businessOrderId).toList());
        payload.put("occurredAt", now);
        return json(payload);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cancellation event serialization failed.", exception);
        }
    }
}
