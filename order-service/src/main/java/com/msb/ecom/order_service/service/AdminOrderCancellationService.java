package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancelRequest;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationPreview;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationResult;
import com.msb.ecom.order_service.model.AdminOrderException;
import com.msb.ecom.order_service.repository.AdminOrderRepository;
import com.msb.ecom.order_service.repository.AdminOrderRepository.AdminCommand;
import com.msb.ecom.order_service.repository.AdminOrderRepository.DetailRow;
import com.msb.ecom.order_service.repository.AdminOrderRepository.GroupRow;
import com.msb.ecom.order_service.repository.AdminOrderRepository.LockedOrder;
import com.msb.ecom.order_service.repository.AdminOrderRepository.PolicyEvidence;
import com.msb.ecom.order_service.security.AdminOrderPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AdminOrderCancellationService {
    private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Set<String> REASONS = Set.of("FRAUD_PREVENTION", "SELLER_UNAVAILABLE",
            "BUYER_REQUEST_CONFIRMED", "POLICY_VIOLATION", "DUPLICATE_ORDER", "OPERATIONAL_ERROR", "OTHER");

    private final AdminOrderService orders;
    private final AdminOrderRepository repository;
    private final CheckoutUlidGenerator ids;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public AdminOrderCancellationService(AdminOrderService orders, AdminOrderRepository repository,
            CheckoutUlidGenerator ids, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this(orders, repository, ids, objectMapper, transactionManager, Clock.systemUTC());
    }

    AdminOrderCancellationService(AdminOrderService orders, AdminOrderRepository repository,
            CheckoutUlidGenerator ids, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.orders = orders;
        this.repository = repository;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Produces a side-effect-free impact assessment from the current authoritative order state.
    public CancellationPreview preview(String rawOrderId, CancelRequest request) {
        orders.require(AdminOrderPermission.CANCEL);
        Validated validated = validate(rawOrderId, request, false);
        DetailRow order = repository.detail(validated.orderId()).orElseThrow(this::notFound);
        requireVersion(order.version(), validated.expectedVersion());
        AdminOrderService.Evaluation evaluation = orders.evaluate(order, repository.groups(order.orderId()),
                repository.policies(order.checkoutId()), clock.instant());
        return preview(order, evaluation);
    }

    // Enters the existing Order-owned cancellation request and compensation workflow atomically.
    public CancellationResult execute(String rawOrderId, CancelRequest request, String correlationId) {
        AdminOrderService.RequestContext context = orders.require(AdminOrderPermission.CANCEL);
        Validated validated = validate(rawOrderId, request, true);
        String actorId = context.access().userId();
        String requestHash = hash(validated);
        AdminCommand existing = repository.command(actorId, validated.idempotencyKey(), false).orElse(null);
        if (existing != null) return replay(existing, requestHash);
        String safeCorrelation = CorrelationId.acceptOrGenerate(correlationId).value();
        CancellationResult response = transactions.execute(status -> executeLocked(
                validated, requestHash, actorId, context.actor().displayName(), safeCorrelation));
        if (response == null) throw unavailable();
        return response;
    }

    private CancellationResult executeLocked(Validated request, String requestHash, String actorId,
            String actorDisplay, String correlationId) {
        AdminCommand existing = repository.command(actorId, request.idempotencyKey(), true).orElse(null);
        if (existing != null) return replay(existing, requestHash);
        LockedOrder locked = repository.lockOrder(request.orderId()).orElseThrow(this::notFound);
        requireVersion(locked.version(), request.expectedVersion());
        DetailRow detail = repository.detail(locked.orderId()).orElseThrow(this::notFound);
        List<GroupRow> groups = repository.lockGroups(locked.orderId());
        AdminOrderService.Evaluation evaluation = orders.evaluate(detail, groups,
                repository.policies(locked.checkoutId()), clock.instant());
        if (!evaluation.allowed()) {
            HttpStatus status = "ORDER_ALREADY_CANCELLED".equals(evaluation.blockerCode())
                    ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
            throw new AdminOrderException(status, evaluation.blockerCode(), evaluation.blockerMessage());
        }
        for (GroupRow group : groups) {
            PolicyEvidence policy = repository.policyEvidence(locked.checkoutId(), group);
            if (policy == null || policy.itemCount() < 1 || policy.mismatchCount() != 0
                    || !"BEFORE_FULFILLMENT".equals(policy.mode())) {
                throw unavailable();
            }
        }
        Instant now = clock.instant();
        String commandId = ids.next();
        String cancellationId = ids.next();
        if (!repository.insertCommand(commandId, actorId, locked.orderId(), request.idempotencyKey(),
                requestHash, locked.version(), now, now.plus(Duration.ofDays(7)))) {
            return replay(repository.command(actorId, request.idempotencyKey(), true)
                    .orElseThrow(this::unavailable), requestHash);
        }
        if (repository.transitionOrder(locked.orderId(), locked.version(), now) != 1) {
            throw new AdminOrderException(HttpStatus.CONFLICT, "ORDER_VERSION_CONFLICT",
                    "Order state changed. Reload before continuing.");
        }
        repository.insertCancellationRequest(cancellationId, locked, actorId, request.reasonCode(),
                request.reason(), correlationId, commandId, now);
        for (GroupRow group : groups) {
            PolicyEvidence policy = repository.policyEvidence(locked.checkoutId(), group);
            repository.insertGroupEvidence(ids.next(), cancellationId, group, policy, now);
            repository.transitionGroup(group.businessOrderId(), now);
            repository.insertGroupHistory(ids.next(), cancellationId, group, correlationId, commandId, now);
        }
        repository.insertOrderHistory(ids.next(), locked.orderId(), actorId, correlationId, commandId, now);
        repository.insertAdminEvent(ids.next(), locked.orderId(), actorId, actorDisplay,
                request.reasonCode(), request.reason(), correlationId, commandId, now);
        String outboxId = ids.next();
        repository.insertOutbox(outboxId, locked.orderId(), event(outboxId, locked, cancellationId,
                groups, actorId, request.reasonCode(), now), correlationId, commandId, now);
        CancellationResult response = new CancellationResult(locked.orderId(), cancellationId,
                "CANCELLATION_REQUESTED", locked.version() + 1, now, false);
        repository.completeCommand(commandId, cancellationId, json(response), now);
        return response;
    }

    private CancellationPreview preview(DetailRow order, AdminOrderService.Evaluation evaluation) {
        List<String> warnings = evaluation.allowed()
                ? List.of("Cancellation completes asynchronously after inventory and refund compensation succeed.",
                        "This action does not restrict the buyer, business, or listing.")
                : List.of(evaluation.blockerMessage());
        return new CancellationPreview(order.orderId(), order.status(), order.version(), evaluation.allowed(),
                evaluation.allowed() ? "CANCELLATION_REQUESTED" : order.status(),
                evaluation.allowed() ? "Restock the committed reservation through the existing compensation worker."
                        : "No inventory mutation will occur.",
                evaluation.allowed() ? "Refund the captured total through the existing idempotent payment adapter."
                        : "No payment or refund mutation will occur.",
                "The buyer account remains unchanged.", "Business and listing enforcement remain unchanged.",
                warnings, evaluation.blockerCode(), evaluation.blockerMessage());
    }

    private Validated validate(String orderId, CancelRequest request, boolean keyRequired) {
        if (orderId == null || !Pattern.matches("[0-7][0-9A-HJKMNP-TV-Z]{25}", orderId)) {
            throw new AdminOrderException(HttpStatus.BAD_REQUEST, "ORDER_ID_INVALID", "Order ID is invalid.");
        }
        if (request == null || request.expectedOrderVersion() == null || request.expectedOrderVersion() < 0) {
            throw new AdminOrderException(HttpStatus.BAD_REQUEST, "ORDER_VERSION_REQUIRED",
                    "The current order version is required.");
        }
        String reasonCode = request.reasonCode() == null ? "" : request.reasonCode().trim().toUpperCase();
        if (!REASONS.contains(reasonCode)) {
            throw new AdminOrderException(HttpStatus.BAD_REQUEST, "ORDER_ADMIN_REASON_INVALID",
                    "A supported administrative cancellation reason is required.");
        }
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.length() > 1000 || ("OTHER".equals(reasonCode) && reason.length() < 3)) {
            throw new AdminOrderException(HttpStatus.BAD_REQUEST, "ORDER_ADMIN_REASON_INVALID",
                    "An explanation is required for the selected reason.");
        }
        String key = request.idempotencyKey();
        if (keyRequired && (key == null || !IDEMPOTENCY.matcher(key).matches())) {
            throw new AdminOrderException(HttpStatus.BAD_REQUEST, "ORDER_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid idempotency key is required.");
        }
        return new Validated(orderId, reasonCode, reason, request.expectedOrderVersion(), key);
    }

    private CancellationResult replay(AdminCommand command, String requestHash) {
        if (!MessageDigest.isEqual(command.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new AdminOrderException(HttpStatus.CONFLICT, "ORDER_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used with a different cancellation request.");
        }
        if (!"COMPLETED".equals(command.state()) || command.responseJson() == null) {
            throw new AdminOrderException(HttpStatus.CONFLICT, "ORDER_ADMIN_ACTION_IN_PROGRESS",
                    "Administrative cancellation is already in progress.");
        }
        try {
            CancellationResult stored = objectMapper.readValue(command.responseJson(), CancellationResult.class);
            return new CancellationResult(stored.orderId(), stored.cancellationRequestId(), stored.status(),
                    stored.version(), stored.requestedAt(), true);
        } catch (JsonProcessingException exception) {
            throw unavailable();
        }
    }

    private String event(String eventId, LockedOrder order, String cancellationId, List<GroupRow> groups,
            String actorId, String reasonCode, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId); payload.put("eventType", "order.cancellation_requested");
        payload.put("eventVersion", 1); payload.put("occurredAt", now); payload.put("orderId", order.orderId());
        payload.put("cancellationRequestId", cancellationId); payload.put("orderStatus", "CANCELLATION_REQUESTED");
        payload.put("requestStatus", "PENDING"); payload.put("orderVersion", order.version() + 1);
        payload.put("businessOrderIds", groups.stream().map(GroupRow::businessOrderId).toList());
        payload.put("actorType", "PLATFORM_ADMIN"); payload.put("actorId", actorId);
        payload.put("reasonCode", reasonCode); return json(payload);
    }

    private String hash(Validated request) {
        return sha256(String.join("\n", request.orderId(), request.reasonCode(), request.reason(),
                Long.toString(request.expectedVersion())));
    }

    private void requireVersion(long current, long expected) {
        if (current != expected) {
            throw new AdminOrderException(HttpStatus.CONFLICT, "ORDER_VERSION_CONFLICT",
                    "Order state changed. Reload before continuing.");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw unavailable(); }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw unavailable(); }
    }

    private AdminOrderException notFound() {
        return new AdminOrderException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order was not found.");
    }

    private AdminOrderException unavailable() {
        return new AdminOrderException(HttpStatus.SERVICE_UNAVAILABLE, "ORDER_ADMIN_ACTION_UNAVAILABLE",
                "Administrative order cancellation is temporarily unavailable.");
    }

    private record Validated(String orderId, String reasonCode, String reason,
            long expectedVersion, String idempotencyKey) { }
}
