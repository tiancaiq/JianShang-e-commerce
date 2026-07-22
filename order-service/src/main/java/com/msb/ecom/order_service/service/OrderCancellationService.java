package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.OrderCancellationProperties;
import com.msb.ecom.order_service.dto.OrderCancellationResponse;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.repository.OrderCancellationRepository;
import com.msb.ecom.order_service.repository.OrderCancellationRepository.CommandRecord;
import com.msb.ecom.order_service.repository.OrderCancellationRepository.GroupRecord;
import com.msb.ecom.order_service.repository.OrderCancellationRepository.OrderRecord;
import com.msb.ecom.order_service.repository.OrderCancellationRepository.PolicyEvidence;
import com.msb.ecom.order_service.repository.OrderCancellationRepository.RequestRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class OrderCancellationService {

    static final String OPERATION = "REQUEST_ORDER_CANCELLATION";
    private static final Pattern ULID =
            Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern IF_MATCH = Pattern.compile("(?:\"([0-9]+)\"|([0-9]+))");
    private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

    private final OrderCancellationProperties properties;
    private final CurrentActorProvider actorProvider;
    private final BuyerIdentityClient buyerIdentityClient;
    private final OrderCancellationRepository repository;
    private final CheckoutUlidGenerator ids;
    private final OrderCancellationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public OrderCancellationService(
            OrderCancellationProperties properties,
            CurrentActorProvider actorProvider,
            BuyerIdentityClient buyerIdentityClient,
            OrderCancellationRepository repository,
            CheckoutUlidGenerator ids,
            OrderCancellationMetrics metrics,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                properties,
                actorProvider,
                buyerIdentityClient,
                repository,
                ids,
                metrics,
                objectMapper,
                transactionManager,
                Clock.systemUTC());
    }

    OrderCancellationService(
            OrderCancellationProperties properties,
            CurrentActorProvider actorProvider,
            BuyerIdentityClient buyerIdentityClient,
            OrderCancellationRepository repository,
            CheckoutUlidGenerator ids,
            OrderCancellationMetrics metrics,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.buyerIdentityClient = buyerIdentityClient;
        this.repository = repository;
        this.ids = ids;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Validates the retry contract, then atomically requests cancellation for every group.
    public OrderCancellationResponse request(
            String orderId,
            String ifMatch,
            String idempotencyKey,
            boolean bodyPresent,
            String correlationId) {
        requireEnabled();
        validateOrderId(orderId);
        if (bodyPresent) {
            reject(HttpStatus.BAD_REQUEST,
                    "ORDER_CANCELLATION_BODY_NOT_ALLOWED",
                    "The cancellation request must not include a body.",
                    "invalid");
        }
        long expectedVersion = parseVersion(ifMatch);
        validateIdempotencyKey(idempotencyKey);
        String safeCorrelationId = safeCorrelation(correlationId);
        String buyerId = resolveBuyer();
        Instant now = clock.instant();
        String requestHash = requestHash(orderId, expectedVersion);

        try {
            if (repository.deleteExpiredCommand(
                    buyerId, OPERATION, idempotencyKey, now) > 0) {
                metrics.command("expired_reclaimed");
            }
            CommandRecord existing =
                    repository.findCommand(buyerId, OPERATION, idempotencyKey).orElse(null);
            if (existing != null) {
                return replay(existing, requestHash);
            }
            OrderCancellationResponse response = executeWithRaceRecovery(
                    orderId,
                    expectedVersion,
                    idempotencyKey,
                    requestHash,
                    buyerId,
                    safeCorrelationId,
                    now);
            purgeExpiredBestEffort(now, safeCorrelationId);
            return response;
        } catch (BuyerOrderException exception) {
            throw exception;
        } catch (DataAccessException | IllegalStateException exception) {
            metrics.command("unavailable");
            log.warn(
                    "Order cancellation unavailable correlation={} order={} result=db_failure",
                    safeCorrelationId,
                    digestForLog(orderId));
            throw new BuyerOrderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDER_CANCELLATION_UNAVAILABLE",
                    "Order cancellation is temporarily unavailable.");
        }
    }

    // Re-reads or retries only translated duplicate/lock races so the durable key wins.
    private OrderCancellationResponse executeWithRaceRecovery(
            String orderId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash,
            String buyerId,
            String correlationId,
            Instant now) {
        try {
            return executeTransaction(
                    orderId,
                    expectedVersion,
                    idempotencyKey,
                    requestHash,
                    buyerId,
                    correlationId,
                    now);
        } catch (DataAccessException exception) {
            if (!isIdempotencyRace(exception)) {
                throw exception;
            }
        }

        CommandRecord winner =
                repository.findCommand(buyerId, OPERATION, idempotencyKey).orElse(null);
        if (winner != null) {
            metrics.command("race_recovered");
            return replay(winner, requestHash);
        }

        try {
            return executeTransaction(
                    orderId,
                    expectedVersion,
                    idempotencyKey,
                    requestHash,
                    buyerId,
                    correlationId,
                    now);
        } catch (DataAccessException exception) {
            if (!isIdempotencyRace(exception)) {
                throw exception;
            }
            winner = repository.findCommand(
                            buyerId, OPERATION, idempotencyKey)
                    .orElse(null);
            if (winner != null) {
                metrics.command("race_recovered");
                return replay(winner, requestHash);
            }
            throw exception;
        }
    }

    private OrderCancellationResponse executeTransaction(
            String orderId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash,
            String buyerId,
            String correlationId,
            Instant now) {
        return transactions.execute(status -> execute(
                orderId,
                expectedVersion,
                idempotencyKey,
                requestHash,
                buyerId,
                correlationId,
                now));
    }

    static boolean isIdempotencyRace(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DuplicateKeyException
                    || current instanceof PessimisticLockingFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void purgeExpiredBestEffort(Instant now, String correlationId) {
        try {
            repository.purgeExpired(now, properties.purgeBatchSize());
        } catch (DataAccessException exception) {
            log.warn(
                    "Order cancellation idempotency purge skipped correlation={} result=db_failure",
                    correlationId);
        }
    }

    // Holds the order and all groups while recording state, evidence, history and outbox.
    private OrderCancellationResponse execute(
            String orderId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash,
            String buyerId,
            String correlationId,
            Instant now) {
        OrderRecord order = repository.lockOwnedOrder(orderId, buyerId)
                .orElseThrow(this::notFound);
        if (!repository.lockBuyerCommandScope(buyerId)) {
            throw new IllegalStateException(
                    "Owned order has no buyer cancellation serialization row.");
        }

        CommandRecord concurrent =
                repository.lockCommand(buyerId, OPERATION, idempotencyKey).orElse(null);
        if (concurrent != null) {
            return replay(concurrent, requestHash);
        }

        if (order.version() != expectedVersion) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_VERSION_CONFLICT",
                    "The order version no longer matches.",
                    "version_conflict");
        }

        if ("CANCELLATION_REQUESTED".equals(order.status())) {
            return semanticReplay(
                    order, buyerId, idempotencyKey, requestHash, correlationId, now);
        }
        if (!"CONFIRMED".equals(order.status()) || !"SUCCEEDED".equals(order.paymentStatus())) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_CANCELLATION_STATE_CONFLICT",
                    "The order is not eligible for a cancellation request.",
                    "state_conflict");
        }

        List<GroupRecord> groups = repository.lockGroups(orderId);
        if (groups.isEmpty()) {
            throw new IllegalStateException("Order has no business groups.");
        }
        List<PolicyEvidence> policies =
                groups.stream().map(group -> requireEligible(order, group, now)).toList();

        String commandId = ids.next();
        String cancellationRequestId = ids.next();
        String outboxId = ids.next();
        if (!repository.insertCommand(
                commandId,
                buyerId,
                orderId,
                OPERATION,
                idempotencyKey,
                requestHash,
                expectedVersion,
                now,
                now.plus(properties.idempotencyRetention()))) {
            return replay(concurrentCommand(buyerId, idempotencyKey), requestHash);
        }
        if (repository.transitionOrder(orderId, buyerId, expectedVersion, now) != 1) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_VERSION_CONFLICT",
                    "The order version no longer matches.",
                    "version_conflict");
        }

        repository.insertRequest(
                cancellationRequestId,
                orderId,
                buyerId,
                expectedVersion,
                now,
                correlationId,
                commandId);
        for (int index = 0; index < groups.size(); index++) {
            GroupRecord group = groups.get(index);
            repository.insertGroupEvidence(
                    ids.next(), cancellationRequestId, group, policies.get(index), now);
            repository.transitionGroup(group.businessOrderId(), now);
            repository.insertGroupHistory(
                    ids.next(), cancellationRequestId, group, correlationId, commandId, now);
        }
        repository.insertOrderHistory(
                ids.next(), orderId, correlationId, commandId, now);
        repository.insertOutbox(
                outboxId,
                orderId,
                eventPayload(
                        outboxId,
                        orderId,
                        cancellationRequestId,
                        expectedVersion + 1,
                        groups,
                        now),
                correlationId,
                commandId,
                now);

        OrderCancellationResponse response = new OrderCancellationResponse(
                orderId,
                cancellationRequestId,
                "CANCELLATION_REQUESTED",
                "PENDING",
                expectedVersion + 1,
                now);
        repository.completeCommand(
                commandId,
                cancellationRequestId,
                json(response),
                Long.toString(response.version()),
                now);
        metrics.command("requested");
        log.info(
                "Order cancellation requested correlation={} order={} result=requested",
                correlationId,
                digestForLog(orderId));
        return response;
    }

    private OrderCancellationResponse semanticReplay(
            OrderRecord order,
            String buyerId,
            String idempotencyKey,
            String requestHash,
            String correlationId,
            Instant now) {
        RequestRecord request = repository.findRequest(order.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cancellation state has no request evidence."));
        String commandId = ids.next();
        OrderCancellationResponse response = response(request);
        if (!repository.insertCommand(
                commandId,
                buyerId,
                order.orderId(),
                OPERATION,
                idempotencyKey,
                requestHash,
                order.version(),
                now,
                now.plus(properties.idempotencyRetention()))) {
            return replay(concurrentCommand(buyerId, idempotencyKey), requestHash);
        }
        repository.completeCommand(
                commandId,
                request.requestId(),
                json(response),
                Long.toString(response.version()),
                now);
        metrics.command("semantic_replay");
        return response;
    }

    private CommandRecord concurrentCommand(String buyerId, String idempotencyKey) {
        return repository.lockCommand(buyerId, OPERATION, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Concurrent cancellation command winner was unavailable."));
    }

    // Rejects any group whose immutable structured policy or cutoff is not eligible.
    private PolicyEvidence requireEligible(OrderRecord order, GroupRecord group, Instant now) {
        if (!"NONE".equals(group.cancellationStatus())) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_CANCELLATION_STATE_CONFLICT",
                    "The order is not eligible for a cancellation request.",
                    "state_conflict");
        }
        PolicyEvidence policy = repository.findPolicyEvidence(
                        order.checkoutId(), group.businessOrderId(), group.businessId())
                .orElseThrow(() -> new IllegalStateException(
                        "Order group has no immutable policy snapshot."));
        if (policy.itemCount() < 1 || policy.mismatchCount() != 0) {
            throw new IllegalStateException("Order policy snapshot evidence is inconsistent.");
        }
        if (!"BEFORE_FULFILLMENT".equals(policy.mode())) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_CANCELLATION_NOT_ALLOWED",
                    "The snapshotted policy does not allow cancellation.",
                    "not_allowed");
        }
        if (group.cancellationCutoffAt() == null
                || !now.isBefore(group.cancellationCutoffAt())) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_CANCELLATION_WINDOW_CLOSED",
                    "The cancellation request window has closed.",
                    "window_closed");
        }
        return policy;
    }

    private OrderCancellationResponse replay(CommandRecord command, String requestHash) {
        if (!MessageDigest.isEqual(
                command.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_CANCELLATION_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was reused with a different request.",
                    "idempotency_conflict");
        }
        if (!"COMPLETED".equals(command.state()) || command.responseJson() == null) {
            reject(HttpStatus.CONFLICT,
                    "ORDER_CANCELLATION_IN_PROGRESS",
                    "The cancellation request is already in progress.",
                    "in_progress");
        }
        try {
            OrderCancellationResponse response =
                    objectMapper.readValue(command.responseJson(), OrderCancellationResponse.class);
            metrics.command("replayed");
            return response;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored cancellation response is invalid.", exception);
        }
    }

    private String eventPayload(
            String eventId,
            String orderId,
            String cancellationRequestId,
            long orderVersion,
            List<GroupRecord> groups,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "order.cancellation_requested");
        payload.put("eventVersion", 1);
        payload.put("occurredAt", occurredAt);
        payload.put("orderId", orderId);
        payload.put("cancellationRequestId", cancellationRequestId);
        payload.put("orderStatus", "CANCELLATION_REQUESTED");
        payload.put("requestStatus", "PENDING");
        payload.put("orderVersion", orderVersion);
        payload.put(
                "businessOrderIds",
                groups.stream().map(GroupRecord::businessOrderId).toList());
        return json(payload);
    }

    private String resolveBuyer() {
        try {
            String buyerId =
                    buyerIdentityClient.resolveBuyer(actorProvider.currentActor().subject());
            if (buyerId == null || buyerId.isBlank()) {
                throw new IllegalStateException("Buyer identity was empty.");
            }
            return buyerId;
        } catch (CheckoutException | IllegalStateException exception) {
            metrics.command("dependency_failure");
            throw new BuyerOrderException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ORDER_CANCELLATION_DEPENDENCY_UNAVAILABLE",
                    "Buyer identity resolution is temporarily unavailable.");
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            metrics.command("disabled");
            throw new BuyerOrderException(
                    HttpStatus.NOT_FOUND,
                    "ORDER_CANCELLATION_NOT_AVAILABLE",
                    "Order cancellation is not available.");
        }
    }

    private void validateOrderId(String orderId) {
        if (orderId == null || !ULID.matcher(orderId).matches()) {
            reject(HttpStatus.BAD_REQUEST,
                    "ORDER_ID_INVALID",
                    "Order ID is invalid.",
                    "invalid");
        }
    }

    private long parseVersion(String ifMatch) {
        if (ifMatch == null) {
            reject(HttpStatus.BAD_REQUEST,
                    "ORDER_VERSION_REQUIRED",
                    "If-Match must contain the current order version.",
                    "invalid");
        }
        var matcher = IF_MATCH.matcher(ifMatch);
        if (!matcher.matches()) {
            reject(HttpStatus.BAD_REQUEST,
                    "ORDER_VERSION_REQUIRED",
                    "If-Match must contain the current order version.",
                    "invalid");
        }
        try {
            return Long.parseLong(matcher.group(1) == null
                    ? matcher.group(2)
                    : matcher.group(1));
        } catch (NumberFormatException exception) {
            reject(HttpStatus.BAD_REQUEST,
                    "ORDER_VERSION_REQUIRED",
                    "If-Match must contain the current order version.",
                    "invalid");
            return -1;
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            reject(HttpStatus.BAD_REQUEST,
                    "ORDER_CANCELLATION_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.",
                    "invalid");
        }
    }

    private String requestHash(String orderId, long expectedVersion) {
        return sha256(OPERATION + "\n" + orderId + "\n" + expectedVersion);
    }

    private String safeCorrelation(String correlationId) {
        if (correlationId == null || !CORRELATION_ID.matcher(correlationId).matches()) {
            return ids.next();
        }
        return correlationId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cancellation payload serialization failed.", exception);
        }
    }

    private OrderCancellationResponse response(RequestRecord request) {
        return new OrderCancellationResponse(
                request.orderId(),
                request.requestId(),
                "CANCELLATION_REQUESTED",
                request.status(),
                request.resultingVersion(),
                request.requestedAt());
    }

    private BuyerOrderException notFound() {
        metrics.command("not_found");
        return new BuyerOrderException(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "Order was not found.");
    }

    private void reject(HttpStatus status, String code, String message, String metric) {
        metrics.command(metric);
        throw new BuyerOrderException(status, code, message);
    }

    private static String digestForLog(String value) {
        return sha256(value).substring(0, 12);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
