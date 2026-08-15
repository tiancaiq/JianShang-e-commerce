package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderFulfillmentProperties;
import com.msb.ecom.order_service.dto.BusinessOrderFulfillmentResponse;
import com.msb.ecom.order_service.dto.CreateManualShipmentRequest;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.repository.BusinessOrderFulfillmentRepository;
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
public class BusinessOrderFulfillmentService {

    private static final String PROCESS = "PROCESS_BUSINESS_ORDER";
    private static final String SHIP = "CREATE_MANUAL_SHIPMENT";
    private static final String DELIVER = "DELIVER_MANUAL_SHIPMENT";
    private static final int CONCURRENCY_ATTEMPTS = 3;
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Pattern BUSINESS_ID = Pattern.compile("[0-9A-Z]{26}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern TRACKING = Pattern.compile("[A-Za-z0-9._:/-]{3,100}");

    private final BusinessOrderFulfillmentProperties properties;
    private final CurrentActorProvider actorProvider;
    private final BusinessOrderAuthorizationClient authorizationClient;
    private final BusinessOrderFulfillmentRepository repository;
    private final CheckoutUlidGenerator ids;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public BusinessOrderFulfillmentService(
            BusinessOrderFulfillmentProperties properties,
            CurrentActorProvider actorProvider,
            BusinessOrderAuthorizationClient authorizationClient,
            BusinessOrderFulfillmentRepository repository,
            CheckoutUlidGenerator ids,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(properties, actorProvider, authorizationClient, repository, ids, objectMapper,
                transactionManager, Clock.systemUTC());
    }

    BusinessOrderFulfillmentService(
            BusinessOrderFulfillmentProperties properties,
            CurrentActorProvider actorProvider,
            BusinessOrderAuthorizationClient authorizationClient,
            BusinessOrderFulfillmentRepository repository,
            CheckoutUlidGenerator ids,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.properties = properties;
        this.actorProvider = actorProvider;
        this.authorizationClient = authorizationClient;
        this.repository = repository;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Moves one authorized accepted business group into seller processing.
    public BusinessOrderFulfillmentResponse startProcessing(
            String businessId,
            String businessOrderId,
            String ifMatch,
            String idempotencyKey,
            String correlationId) {
        if (!properties.processingEnabled()) {
            throw unavailableFeature("BUSINESS_ORDER_PROCESSING_NOT_AVAILABLE");
        }
        RequestContext request = request(
                businessId, businessOrderId, ifMatch, idempotencyKey,
                PROCESS, "");
        return execute(request, () -> processTransaction(request, correlationId));
    }

    // Creates the single local-demo manual shipment and ships the group atomically.
    public BusinessOrderFulfillmentResponse createShipment(
            String businessId,
            String businessOrderId,
            String ifMatch,
            String idempotencyKey,
            CreateManualShipmentRequest body,
            String correlationId) {
        if (!properties.manualShipmentEnabled()) {
            throw unavailableFeature("MANUAL_SHIPMENT_NOT_AVAILABLE");
        }
        ShipmentInput input = shipmentInput(body);
        RequestContext request = request(
                businessId, businessOrderId, ifMatch, idempotencyKey, SHIP,
                input.carrier() + '|' + input.service() + '|' + input.tracking() + '|'
                        + input.shippedAt());
        return execute(request,
                () -> shipmentTransaction(request, input, correlationId));
    }

    // Simulates delivery locally; it never represents a carrier callback.
    public BusinessOrderFulfillmentResponse recordDemoDelivery(
            String businessId,
            String businessOrderId,
            String ifMatch,
            String idempotencyKey,
            String correlationId) {
        if (!properties.demoDeliveryEnabled()) {
            throw unavailableFeature("DEMO_DELIVERY_NOT_AVAILABLE");
        }
        RequestContext request = request(
                businessId, businessOrderId, ifMatch, idempotencyKey, DELIVER,
                "");
        return execute(request,
                () -> deliveryTransaction(request, correlationId));
    }

    private BusinessOrderFulfillmentResponse execute(
            RequestContext request,
            java.util.function.Supplier<BusinessOrderFulfillmentResponse> mutation) {
        Instant now = clock.instant();
        repository.purgeExpired(now, properties.purgeBatchSize());
        for (int attempt = 1; attempt <= CONCURRENCY_ATTEMPTS; attempt++) {
            try {
                return Objects.requireNonNull(transactions.execute(status -> mutation.get()));
            } catch (TransientDataAccessException exception) {
                if (attempt == CONCURRENCY_ATTEMPTS) {
                    throw unavailable();
                }
            }
        }
        throw unavailable();
    }

    private BusinessOrderFulfillmentResponse processTransaction(
            RequestContext request,
            String correlationId) {
        Instant now = clock.instant();
        BusinessOrderFulfillmentRepository.Command command = claim(request, now);
        if (command != null) {
            return replay(command, request);
        }
        BusinessOrderFulfillmentRepository.GroupState group = lockGroup(request);
        requireState(group, request.expectedVersion(), "ACCEPTED");
        long version = request.expectedVersion() + 1;
        transition(group, version, "PROCESSING", "BUSINESS_PROCESSING_STARTED",
                "business_order.processing_started", request.commandId(),
                request.actorUserId(), correlationId, now, null);
        return response(group.businessOrderId(), "PROCESSING", version, now, null);
    }

    private BusinessOrderFulfillmentResponse shipmentTransaction(
            RequestContext request,
            ShipmentInput input,
            String correlationId) {
        Instant now = clock.instant();
        BusinessOrderFulfillmentRepository.Command command = claim(request, now);
        if (command != null) {
            return replay(command, request);
        }
        BusinessOrderFulfillmentRepository.GroupState group = lockGroup(request);
        requireState(group, request.expectedVersion(), "PROCESSING");
        if (input.shippedAt().isBefore(group.createdAt())
                || input.shippedAt().isAfter(now.plusSeconds(300))) {
            throw conflict("MANUAL_SHIPMENT_TIME_INVALID",
                    "Shipped time must be after order confirmation and not in the future.");
        }
        String shipmentId = ids.next();
        try {
            repository.insertShipment(
                    shipmentId, group, input.carrier(), input.service(), input.tracking(),
                    input.shippedAt(), now);
        } catch (DuplicateKeyException exception) {
            throw conflict("MANUAL_SHIPMENT_CONFLICT",
                    "A shipment already exists or the tracking number is already in use.");
        }
        repository.insertShipmentHistory(
                ids.next(), shipmentId, group, "NOT_CREATED", "SHIPPED", 0,
                "MANUAL_SHIPMENT_CREATED", request.actorUserId(), correlationId,
                request.commandId(), now);
        long version = request.expectedVersion() + 1;
        transition(group, version, "SHIPPED", "MANUAL_SHIPMENT_CREATED",
                "business_order.shipped", request.commandId(), request.actorUserId(),
                correlationId, now, shipmentId);
        BusinessOrderFulfillmentRepository.Shipment shipment =
                repository.findOwnedShipment(group.businessId(), group.businessOrderId())
                        .orElseThrow(this::unavailable);
        return response(group.businessOrderId(), "SHIPPED", version, now, shipment);
    }

    private BusinessOrderFulfillmentResponse deliveryTransaction(
            RequestContext request,
            String correlationId) {
        Instant now = clock.instant();
        BusinessOrderFulfillmentRepository.Command command = claim(request, now);
        if (command != null) {
            return replay(command, request);
        }
        BusinessOrderFulfillmentRepository.GroupState group = lockGroup(request);
        requireState(group, request.expectedVersion(), "SHIPPED");
        BusinessOrderFulfillmentRepository.Shipment shipment =
                repository.findOwnedShipment(group.businessId(), group.businessOrderId())
                        .orElseThrow(this::unavailable);
        if (repository.deliverShipment(
                shipment.shipmentId(), group.businessId(), group.businessOrderId(), now) != 1) {
            throw stateConflict();
        }
        repository.insertShipmentHistory(
                ids.next(), shipment.shipmentId(), group, "SHIPPED", "DELIVERED", 1,
                "LOCAL_DEMO_DELIVERY_RECORDED", request.actorUserId(), correlationId,
                request.commandId(), now);
        long version = request.expectedVersion() + 1;
        transition(group, version, "DELIVERED", "LOCAL_DEMO_DELIVERY_RECORDED",
                "business_order.delivered_demo", request.commandId(), request.actorUserId(),
                correlationId, now, shipment.shipmentId());
        BusinessOrderFulfillmentRepository.Shipment delivered =
                repository.findOwnedShipment(group.businessId(), group.businessOrderId())
                        .orElseThrow(this::unavailable);
        return response(group.businessOrderId(), "DELIVERED", version, now, delivered);
    }

    private BusinessOrderFulfillmentRepository.Command claim(
            RequestContext request,
            Instant now) {
        BusinessOrderFulfillmentRepository.Command existing = repository.lockCommand(
                request.actorUserId(), request.businessId(), request.operation(),
                request.idempotencyKey()).orElse(null);
        if (existing != null) {
            requireReplayHash(existing, request);
            return existing;
        }
        try {
            repository.insertCommand(
                    request.commandId(), request.actorUserId(), request.businessId(),
                    request.operation(), request.idempotencyKey(), request.requestHash(),
                    request.businessOrderId(), now, now.plus(properties.retention()));
            return null;
        } catch (DuplicateKeyException exception) {
            BusinessOrderFulfillmentRepository.Command concurrent = repository.lockCommand(
                            request.actorUserId(), request.businessId(), request.operation(),
                            request.idempotencyKey())
                    .orElseThrow(this::unavailable);
            requireReplayHash(concurrent, request);
            return concurrent;
        }
    }

    private BusinessOrderFulfillmentRepository.GroupState lockGroup(RequestContext request) {
        return repository.lockOwnedGroup(request.businessId(), request.businessOrderId())
                .orElseThrow(this::notFound);
    }

    private void requireState(
            BusinessOrderFulfillmentRepository.GroupState group,
            long expectedVersion,
            String expectedStatus) {
        if (group.version() != expectedVersion) {
            throw conflict("BUSINESS_ORDER_VERSION_CONFLICT",
                    "Business order version does not match.");
        }
        if (!expectedStatus.equals(group.fulfillmentStatus())
                || !"NONE".equals(group.cancellationStatus())) {
            throw stateConflict();
        }
        if (!"SUCCEEDED".equals(group.paymentStatus())) {
            throw conflict("BUSINESS_ORDER_NOT_PAID", "Business order payment is not complete.");
        }
    }

    private void transition(
            BusinessOrderFulfillmentRepository.GroupState group,
            long version,
            String toStatus,
            String reason,
            String eventType,
            String commandId,
            String actorUserId,
            String correlationId,
            Instant now,
            String shipmentId) {
        if (repository.transitionGroup(
                group.businessId(), group.businessOrderId(), group.version(),
                group.fulfillmentStatus(), toStatus, now) != 1) {
            throw stateConflict();
        }
        repository.insertGroupHistory(
                ids.next(), group, toStatus, version, reason, actorUserId,
                correlationId, commandId, now);
        String eventId = ids.next();
        repository.insertOutbox(
                eventId, group.businessOrderId(), eventType,
                payload(eventId, eventType, group, toStatus, version, shipmentId, now),
                correlationId, commandId, now);
        repository.completeCommand(commandId, version, now, shipmentId, now);
    }

    private BusinessOrderFulfillmentResponse replay(
            BusinessOrderFulfillmentRepository.Command command,
            RequestContext request) {
        requireReplayHash(command, request);
        if (!"COMPLETED".equals(command.state())
                || command.resultVersion() == null || command.resultUpdatedAt() == null) {
            throw unavailable();
        }
        BusinessOrderFulfillmentRepository.Shipment shipment =
                command.resultShipmentId() == null ? null : repository.findOwnedShipment(
                        request.businessId(), command.businessOrderId()).orElseThrow(this::unavailable);
        String resultStatus = switch (request.operation()) {
            case PROCESS -> "PROCESSING";
            case SHIP -> "SHIPPED";
            case DELIVER -> "DELIVERED";
            default -> throw unavailable();
        };
        if (SHIP.equals(request.operation()) && shipment != null) {
            return new BusinessOrderFulfillmentResponse(
                    command.businessOrderId(), resultStatus, command.resultVersion(),
                    command.resultUpdatedAt(), new BusinessOrderFulfillmentResponse.Shipment(
                    shipment.shipmentId(), shipment.source(), shipment.carrierDisplayName(),
                    shipment.serviceDisplayName(), shipment.trackingNumber(), "SHIPPED", 0,
                    shipment.shippedAt(), null, shipment.createdAt(), command.resultUpdatedAt()));
        }
        return response(command.businessOrderId(), resultStatus,
                command.resultVersion(), command.resultUpdatedAt(), shipment);
    }

    private void requireReplayHash(
            BusinessOrderFulfillmentRepository.Command command,
            RequestContext request) {
        if (!request.requestHash().equals(command.requestHash())) {
            throw conflict("BUSINESS_ORDER_IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was reused with a different request.");
        }
    }

    private RequestContext request(
            String businessId,
            String businessOrderId,
            String ifMatch,
            String idempotencyKey,
            String operation,
            String hashMaterial) {
        requireBusinessId(businessId);
        requireId(businessOrderId);
        long expectedVersion = expectedVersion(ifMatch);
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new BusinessOrderException(HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
        CurrentActor actor = actorProvider.currentActor();
        if (actor.accessToken() == null || actor.accessToken().isBlank()) {
            throw dependencyUnavailable();
        }
        BusinessOrderAuthorizationClient.Access access;
        try {
            access = authorizationClient.authorizeFulfillment(actor.accessToken(), businessId);
        } catch (BusinessOrderException exception) {
            if (exception.status() == HttpStatus.NOT_FOUND) {
                throw exception;
            }
            throw dependencyUnavailable();
        }
        return new RequestContext(
                access.userId(), businessId, businessOrderId, expectedVersion,
                idempotencyKey, operation,
                sha256(operation + '|' + businessId + '|' + businessOrderId + '|'
                        + expectedVersion + '|' + hashMaterial),
                ids.next());
    }

    private ShipmentInput shipmentInput(CreateManualShipmentRequest body) {
        if (body == null || body.shippedAt() == null) {
            throw invalidShipment("Manual shipment fields and shipped time are required.");
        }
        String carrier = bounded(body.carrierDisplayName(), 80, "Carrier display name");
        String service = bounded(body.serviceDisplayName(), 80, "Service display name");
        String tracking = body.trackingNumber() == null ? "" : body.trackingNumber().trim();
        if (!TRACKING.matcher(tracking).matches()) {
            throw invalidShipment("Tracking number must contain 3 to 100 safe characters.");
        }
        return new ShipmentInput(carrier, service, tracking, body.shippedAt());
    }

    private String bounded(String value, int maximum, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw invalidShipment(label + " is required and must be at most " + maximum + " characters.");
        }
        return normalized;
    }

    private BusinessOrderFulfillmentResponse response(
            String businessOrderId,
            String status,
            long version,
            Instant updatedAt,
            BusinessOrderFulfillmentRepository.Shipment shipment) {
        return new BusinessOrderFulfillmentResponse(
                businessOrderId, status, version, updatedAt,
                shipment == null ? null : new BusinessOrderFulfillmentResponse.Shipment(
                        shipment.shipmentId(), shipment.source(), shipment.carrierDisplayName(),
                        shipment.serviceDisplayName(), shipment.trackingNumber(), shipment.status(),
                        shipment.version(), shipment.shippedAt(), shipment.deliveredAt(),
                        shipment.createdAt(), shipment.updatedAt()));
    }

    private String payload(
            String eventId,
            String eventType,
            BusinessOrderFulfillmentRepository.GroupState group,
            String status,
            long version,
            String shipmentId,
            Instant occurredAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", eventId);
        payload.put("eventType", eventType);
        payload.put("eventVersion", 1);
        payload.put("occurredAt", occurredAt.toString());
        payload.put("businessOrderId", group.businessOrderId());
        payload.put("orderId", group.orderId());
        payload.put("businessId", group.businessId());
        payload.put("fulfillmentStatus", status);
        payload.put("businessOrderVersion", version);
        if (shipmentId != null) {
            payload.put("shipmentId", shipmentId);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private void requireId(String value) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new BusinessOrderException(HttpStatus.BAD_REQUEST,
                    "BUSINESS_ORDER_ID_INVALID", "Business order identifier is invalid.");
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

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private BusinessOrderException unavailableFeature(String code) {
        return new BusinessOrderException(HttpStatus.NOT_FOUND, code,
                "Business order fulfillment action is not available.");
    }

    private BusinessOrderException versionRequired() {
        return new BusinessOrderException(HttpStatus.BAD_REQUEST,
                "BUSINESS_ORDER_VERSION_REQUIRED",
                "If-Match must contain the current business order version.");
    }

    private BusinessOrderException invalidShipment(String message) {
        return new BusinessOrderException(HttpStatus.BAD_REQUEST,
                "MANUAL_SHIPMENT_INVALID", message);
    }

    private BusinessOrderException conflict(String code, String message) {
        return new BusinessOrderException(HttpStatus.CONFLICT, code, message);
    }

    private BusinessOrderException stateConflict() {
        return conflict("BUSINESS_ORDER_STATE_CONFLICT",
                "Business order cannot perform this action from its current state.");
    }

    private BusinessOrderException notFound() {
        return new BusinessOrderException(HttpStatus.NOT_FOUND,
                "BUSINESS_ORDER_NOT_FOUND", "Business order was not found.");
    }

    private BusinessOrderException dependencyUnavailable() {
        return new BusinessOrderException(HttpStatus.SERVICE_UNAVAILABLE,
                "BUSINESS_ORDER_FULFILLMENT_DEPENDENCY_UNAVAILABLE",
                "Business order fulfillment is temporarily unavailable.");
    }

    private BusinessOrderException unavailable() {
        return new BusinessOrderException(HttpStatus.SERVICE_UNAVAILABLE,
                "BUSINESS_ORDER_FULFILLMENT_UNAVAILABLE",
                "Business order fulfillment is temporarily unavailable.");
    }

    private record RequestContext(
            String actorUserId, String businessId, String businessOrderId,
            long expectedVersion, String idempotencyKey, String operation,
            String requestHash, String commandId) {}

    private record ShipmentInput(
            String carrier, String service, String tracking, Instant shippedAt) {}
}
