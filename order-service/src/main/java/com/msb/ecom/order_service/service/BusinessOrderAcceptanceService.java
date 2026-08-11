package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderAcceptanceProperties;
import com.msb.ecom.order_service.dto.BusinessOrderAcceptanceResponse;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderAcceptanceCommand;
import com.msb.ecom.order_service.repository.BusinessOrderAcceptanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class BusinessOrderAcceptanceService {

    private static final Logger log =
            LoggerFactory.getLogger(BusinessOrderAcceptanceService.class);
    private static final String OPERATION = "ACCEPT_BUSINESS_ORDER";
    private static final int CONCURRENCY_ATTEMPTS = 3;
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern BUSINESS_ID = Pattern.compile("[0-9A-Z]{26}");
    private static final Pattern IDEMPOTENCY_KEY =
            Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private final BusinessOrderAcceptanceProperties properties;
    private final CurrentActorProvider actorProvider;
    private final BusinessOrderAuthorizationClient authorizationClient;
    private final BusinessOrderAcceptanceRepository repository;
    private final BusinessOrderMetrics metrics;
    private final CheckoutUlidGenerator ids;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public BusinessOrderAcceptanceService(
            BusinessOrderAcceptanceProperties properties,
            CurrentActorProvider actorProvider,
            BusinessOrderAuthorizationClient authorizationClient,
            BusinessOrderAcceptanceRepository repository,
            BusinessOrderMetrics metrics,
            CheckoutUlidGenerator ids,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                properties,
                actorProvider,
                authorizationClient,
                repository,
                metrics,
                ids,
                objectMapper,
                transactionManager,
                Clock.systemUTC());
    }

    BusinessOrderAcceptanceService(
            BusinessOrderAcceptanceProperties properties,
            CurrentActorProvider actorProvider,
            BusinessOrderAuthorizationClient authorizationClient,
            BusinessOrderAcceptanceRepository repository,
            BusinessOrderMetrics metrics,
            CheckoutUlidGenerator ids,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.authorizationClient = authorizationClient;
        this.repository = repository;
        this.metrics = metrics;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Accepts one paid, business-owned fulfillment group with durable replay safety.
    public BusinessOrderAcceptanceResponse accept(
            String businessId,
            String businessOrderId,
            String ifMatch,
            String idempotencyKey,
            String correlationId) {
        requireEnabled();
        requireBusinessId(businessId);
        requireId(businessOrderId);
        long expectedVersion = expectedVersion(ifMatch);
        requireIdempotencyKey(idempotencyKey);

        CurrentActor actor = actorProvider.currentActor();
        if (actor.accessToken() == null || actor.accessToken().isBlank()) {
            metrics.accept("dependency_failure");
            throw dependencyUnavailable();
        }
        BusinessOrderAuthorizationClient.Access access;
        try {
            access = authorizationClient.authorizeFulfillment(actor.accessToken(), businessId);
        } catch (BusinessOrderException exception) {
            metrics.accept(exception.status() == HttpStatus.NOT_FOUND
                    ? "not_found"
                    : "dependency_failure");
            throw exception;
        }

        String requestHash = requestHash(
                businessId, businessOrderId, expectedVersion);
        Instant now = clock.instant();
        try {
            // Expiry cleanup is independent maintenance and must not widen mutation lock ranges.
            repository.purgeExpired(now, properties.purgeBatchSize());
            AcceptanceResult result = executeWithConcurrencyRetry(
                    access.userId(),
                    businessId,
                    businessOrderId,
                    expectedVersion,
                    idempotencyKey,
                    requestHash,
                    correlationId,
                    now);
            metrics.accept(result.replayed() ? "replayed" : "accepted");
            log.info(
                    "Business order acceptance result={} correlationId={} businessRef={} orderRef={}",
                    result.replayed() ? "replayed" : "accepted",
                    correlationId,
                    safeReference(businessId),
                    safeReference(businessOrderId));
            return result.response();
        } catch (BusinessOrderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            metrics.accept("dependency_failure");
            log.warn(
                    "Business order acceptance unavailable correlationId={} businessRef={} orderRef={}",
                    correlationId,
                    safeReference(businessId),
                    safeReference(businessOrderId));
            throw unavailable();
        }
    }

    private AcceptanceResult acceptTransaction(
            String actorUserId,
            String businessId,
            String businessOrderId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash,
            String correlationId,
            Instant now) {
        BusinessOrderAcceptanceCommand existing = repository.lockCommand(
                actorUserId, businessId, OPERATION, idempotencyKey).orElse(null);
        if (existing != null) {
            return replay(existing, requestHash);
        }

        String commandId = ids.next();
        try {
            repository.insertCommand(
                    commandId,
                    actorUserId,
                    businessId,
                    OPERATION,
                    idempotencyKey,
                    requestHash,
                    businessOrderId,
                    now,
                    now.plus(properties.retention()));
        } catch (DuplicateKeyException exception) {
            BusinessOrderAcceptanceCommand concurrent = repository.lockCommand(
                            actorUserId, businessId, OPERATION, idempotencyKey)
                    .orElseThrow(this::unavailable);
            return replay(concurrent, requestHash);
        }

        BusinessOrderAcceptanceRepository.GroupState group =
                repository.lockOwnedGroup(businessId, businessOrderId)
                        .orElseThrow(this::notFound);
        requirePreconditions(group, expectedVersion);

        long acceptedVersion = expectedVersion + 1;
        if (repository.accept(
                businessId, businessOrderId, expectedVersion, now) != 1) {
            metrics.accept("state_conflict");
            throw stateConflict();
        }

        String historyId = ids.next();
        repository.insertHistory(
                historyId,
                group,
                acceptedVersion,
                actorUserId,
                correlationId,
                commandId,
                now);
        String eventId = ids.next();
        repository.insertOutbox(
                eventId,
                businessOrderId,
                acceptedPayload(
                        eventId,
                        group,
                        acceptedVersion,
                        now),
                correlationId,
                commandId,
                now);
        repository.completeCommand(commandId, acceptedVersion, now, now);

        return new AcceptanceResult(
                response(businessOrderId, acceptedVersion, now),
                false);
    }

    // Retries only database concurrency victims; business conflicts remain deterministic.
    private AcceptanceResult executeWithConcurrencyRetry(
            String actorUserId,
            String businessId,
            String businessOrderId,
            long expectedVersion,
            String idempotencyKey,
            String requestHash,
            String correlationId,
            Instant now) {
        for (int attempt = 1; attempt <= CONCURRENCY_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(transactions.execute(status ->
                        acceptTransaction(
                                actorUserId,
                                businessId,
                                businessOrderId,
                                expectedVersion,
                                idempotencyKey,
                                requestHash,
                                correlationId,
                                now)));
            } catch (TransientDataAccessException exception) {
                if (attempt == CONCURRENCY_ATTEMPTS) {
                    throw exception;
                }
            }
        }
        throw unavailable();
    }

    private AcceptanceResult replay(
            BusinessOrderAcceptanceCommand command,
            String requestHash) {
        if (!requestHash.equals(command.requestHash())) {
            metrics.accept("idempotency_conflict");
            throw new BusinessOrderException(
                    HttpStatus.CONFLICT,
                    "BUSINESS_ORDER_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was reused with a different request.");
        }
        if (!"COMPLETED".equals(command.state())
                || command.resultVersion() == null
                || command.resultUpdatedAt() == null) {
            throw unavailable();
        }
        return new AcceptanceResult(
                response(
                        command.businessOrderId(),
                        command.resultVersion(),
                        command.resultUpdatedAt()),
                true);
    }

    private void requirePreconditions(
            BusinessOrderAcceptanceRepository.GroupState group,
            long expectedVersion) {
        if (group.version() != expectedVersion) {
            metrics.accept("version_conflict");
            throw new BusinessOrderException(
                    HttpStatus.CONFLICT,
                    "BUSINESS_ORDER_VERSION_CONFLICT",
                    "Business order version does not match.");
        }
        if (!"PENDING_ACCEPTANCE".equals(group.fulfillmentStatus())
                || !"NONE".equals(group.cancellationStatus())) {
            metrics.accept("state_conflict");
            throw stateConflict();
        }
        if (!"SUCCEEDED".equals(group.paymentStatus())) {
            metrics.accept("not_paid");
            throw new BusinessOrderException(
                    HttpStatus.CONFLICT,
                    "BUSINESS_ORDER_NOT_PAID",
                    "Business order payment is not complete.");
        }
    }

    private String acceptedPayload(
            String eventId,
            BusinessOrderAcceptanceRepository.GroupState group,
            long acceptedVersion,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", "business_order.accepted");
        payload.put("eventVersion", 1);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("businessOrderId", group.businessOrderId());
        payload.put("orderId", group.orderId());
        payload.put("businessId", group.businessId());
        payload.put("fulfillmentStatus", "ACCEPTED");
        payload.put("businessOrderVersion", acceptedVersion);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private BusinessOrderAcceptanceResponse response(
            String businessOrderId,
            long version,
            Instant updatedAt) {
        return new BusinessOrderAcceptanceResponse(
                businessOrderId,
                "ACCEPTED",
                version,
                updatedAt);
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            metrics.accept("disabled");
            throw new BusinessOrderException(
                    HttpStatus.NOT_FOUND,
                    "BUSINESS_ORDER_ACCEPTANCE_NOT_AVAILABLE",
                    "Business order acceptance is not available.");
        }
    }

    private void requireId(String value) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new BusinessOrderException(
                    HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_ID_INVALID",
                    "Business order identifier is invalid.");
        }
    }

    private void requireBusinessId(String value) {
        if (value == null || !BUSINESS_ID.matcher(value).matches()) {
            throw new BusinessOrderException(HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_ID_INVALID", "Business order identifier is invalid.");
        }
    }

    private long expectedVersion(String ifMatch) {
        if (ifMatch == null) {
            throw versionRequired();
        }
        String value = ifMatch;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            throw versionRequired();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw versionRequired();
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BusinessOrderException(
                    HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
    }

    private String requestHash(
            String businessId,
            String businessOrderId,
            long expectedVersion) {
        return sha256(String.join(
                "|",
                OPERATION,
                businessId,
                businessOrderId,
                Long.toString(expectedVersion)));
    }

    private String safeReference(String value) {
        return sha256(value).substring(0, 12);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private BusinessOrderException versionRequired() {
        return new BusinessOrderException(
                HttpStatus.BAD_REQUEST,
                "BUSINESS_ORDER_VERSION_REQUIRED",
                "If-Match must contain the current business order version.");
    }

    private BusinessOrderException stateConflict() {
        return new BusinessOrderException(
                HttpStatus.CONFLICT,
                "BUSINESS_ORDER_STATE_CONFLICT",
                "Business order cannot be accepted from its current state.");
    }

    private BusinessOrderException notFound() {
        return new BusinessOrderException(
                HttpStatus.NOT_FOUND,
                "BUSINESS_ORDER_NOT_FOUND",
                "Business order was not found.");
    }

    private BusinessOrderException dependencyUnavailable() {
        return new BusinessOrderException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "BUSINESS_ORDER_ACCEPTANCE_DEPENDENCY_UNAVAILABLE",
                "Business order acceptance is temporarily unavailable.");
    }

    private BusinessOrderException unavailable() {
        return new BusinessOrderException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "BUSINESS_ORDER_ACCEPTANCE_UNAVAILABLE",
                "Business order acceptance is temporarily unavailable.");
    }

    private record AcceptanceResult(
            BusinessOrderAcceptanceResponse response,
            boolean replayed
    ) {
    }
}
