package com.msb.ecom.inventory_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.inventory_service.dto.InventoryReservationItemResponse;
import com.msb.ecom.inventory_service.dto.InventoryReservationReleaseRequest;
import com.msb.ecom.inventory_service.dto.InventoryReservationRequest;
import com.msb.ecom.inventory_service.dto.InventoryReservationResponse;
import com.msb.ecom.inventory_service.model.InventoryException;
import com.msb.ecom.inventory_service.model.ReservationPurpose;
import com.msb.ecom.inventory_service.model.ReservationReleaseReason;
import com.msb.ecom.inventory_service.model.ReservationStatus;
import com.msb.ecom.inventory_service.repository.IdempotencyRecord;
import com.msb.ecom.inventory_service.repository.InventoryItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryRepository;
import com.msb.ecom.inventory_service.repository.InventoryReservationItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryReservationRecord;
import com.msb.ecom.inventory_service.repository.InventoryReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class InventoryReservationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    private static final int MAX_ITEMS = 50;
    private static final int MAX_QUANTITY = 999;

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final InternalCommerceAuthenticator authenticator;
    private final InventoryReservationMetrics metrics;
    private final UlidGenerator ulidGenerator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration maxLifetime;
    private final int expiryBatchSize;

    @Autowired
    public InventoryReservationService(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            InternalCommerceAuthenticator authenticator,
            InventoryReservationMetrics metrics,
            UlidGenerator ulidGenerator,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${inventory.reservations.max-lifetime:PT24H}") Duration maxLifetime,
            @Value("${inventory.reservations.expiry-batch-size:50}") int expiryBatchSize) {
        this(
                inventoryRepository,
                reservationRepository,
                authenticator,
                metrics,
                ulidGenerator,
                objectMapper,
                transactionManager,
                Clock.systemUTC(),
                maxLifetime,
                expiryBatchSize);
    }

    protected InventoryReservationService(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            InternalCommerceAuthenticator authenticator,
            InventoryReservationMetrics metrics,
            UlidGenerator ulidGenerator,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock,
            Duration maxLifetime,
            int expiryBatchSize) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.authenticator = authenticator;
        this.metrics = metrics;
        this.ulidGenerator = ulidGenerator;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        if (maxLifetime == null || maxLifetime.isZero() || maxLifetime.isNegative()) {
            throw new IllegalArgumentException("Reservation maximum lifetime must be positive.");
        }
        if (expiryBatchSize < 1 || expiryBatchSize > 500) {
            throw new IllegalArgumentException("Reservation expiry batch size must be from 1 through 500.");
        }
        this.maxLifetime = maxLifetime;
        this.expiryBatchSize = expiryBatchSize;
    }

    // Atomically reserves every requested listing or records one stable all-or-nothing failure.
    public InventoryReservationResponse reserve(
            String suppliedToken,
            String idempotencyKey,
            InventoryReservationRequest request,
            String correlationId) {
        authenticator.requireAuthenticated(suppliedToken);
        Instant now = clock.instant();
        ValidatedReserve validated = validateReserve(request);
        String key = requiredIdempotencyKey(idempotencyKey);
        String callerScope = "ORDER_SERVICE:RESERVE:" + validated.checkoutId() + ":" + validated.purpose();
        String requestHash = reserveHash(validated);
        CommandOutcome replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            metrics.command("RESERVE", "REPLAY");
            log.info("Replayed inventory reservation checkoutId={} purpose={}",
                    validated.checkoutId(), validated.purpose());
            return replay.orThrow();
        }
        requireNewReservationLifetime(validated, now);

        CommandOutcome outcome;
        try {
            outcome = Objects.requireNonNull(transactions.execute(status ->
                    reserveTransaction(validated, callerScope, key, requestHash, correlationId, now)));
        } catch (DuplicateKeyException exception) {
            CommandOutcome concurrentReplay = replay(callerScope, key, requestHash);
            if (concurrentReplay != null) {
                metrics.command("RESERVE", "CONCURRENT_REPLAY");
                return concurrentReplay.orThrow();
            }
            metrics.command("RESERVE", "ALREADY_EXISTS");
            throw conflict(
                    "INVENTORY_RESERVATION_ALREADY_EXISTS",
                    "This checkout already has an inventory reservation.");
        }
        return outcome.orThrow();
    }

    public InventoryReservationResponse get(String suppliedToken, String reservationId) {
        authenticator.requireAuthenticated(suppliedToken);
        String normalizedId = requiredId("Reservation ID", reservationId);
        InventoryReservationRecord reservation = reservationRepository.find(normalizedId)
                .orElseThrow(this::notFound);
        return response(reservation, reservationRepository.findItems(normalizedId), clock.instant());
    }

    // Releases active stock once, while treating a passed deadline as an expiry transition.
    public InventoryReservationResponse release(
            String suppliedToken,
            String reservationId,
            String idempotencyKey,
            InventoryReservationReleaseRequest request,
            String correlationId) {
        authenticator.requireAuthenticated(suppliedToken);
        String normalizedId = requiredId("Reservation ID", reservationId);
        ReservationReleaseReason reason = request == null ? null : request.reason();
        if (reason == null || reason == ReservationReleaseReason.EXPIRY) {
            throw invalid("A supported explicit release reason is required.");
        }
        String key = requiredIdempotencyKey(idempotencyKey);
        String callerScope = "ORDER_SERVICE:RELEASE:" + normalizedId;
        String requestHash = hash("RELEASE", normalizedId, reason.name());
        CommandOutcome replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            metrics.command("RELEASE", "REPLAY");
            return replay.orThrow();
        }
        Instant now = clock.instant();
        try {
            return Objects.requireNonNull(transactions.execute(status -> releaseTransaction(
                    normalizedId,
                    reason,
                    callerScope,
                    key,
                    requestHash,
                    correlationId,
                    now))).orThrow();
        } catch (DuplicateKeyException exception) {
            CommandOutcome concurrentReplay = replay(callerScope, key, requestHash);
            if (concurrentReplay != null) {
                metrics.command("RELEASE", "CONCURRENT_REPLAY");
                return concurrentReplay.orThrow();
            }
            throw exception;
        }
    }

    // Commits a still-usable reservation once and emits a durable signal for recovery-required rejection.
    public InventoryReservationResponse commit(
            String suppliedToken,
            String reservationId,
            String idempotencyKey,
            String correlationId) {
        authenticator.requireAuthenticated(suppliedToken);
        String normalizedId = requiredId("Reservation ID", reservationId);
        String key = requiredIdempotencyKey(idempotencyKey);
        String callerScope = "ORDER_SERVICE:COMMIT:" + normalizedId;
        String requestHash = hash("COMMIT", normalizedId);
        CommandOutcome replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            metrics.command("COMMIT", "REPLAY");
            return replay.orThrow();
        }
        Instant now = clock.instant();
        try {
            return Objects.requireNonNull(transactions.execute(status -> commitTransaction(
                    normalizedId,
                    callerScope,
                    key,
                    requestHash,
                    correlationId,
                    now))).orThrow();
        } catch (DuplicateKeyException exception) {
            CommandOutcome concurrentReplay = replay(callerScope, key, requestHash);
            if (concurrentReplay != null) {
                metrics.command("COMMIT", "CONCURRENT_REPLAY");
                return concurrentReplay.orThrow();
            }
            throw exception;
        }
    }

    // Claims and expires one bounded due batch using database row locks.
    public int expireDueReservations() {
        Instant now = clock.instant();
        Integer expired = transactions.execute(status -> {
            List<String> ids = reservationRepository.lockExpiredReservationIds(now, expiryBatchSize);
            int count = 0;
            for (String id : ids) {
                InventoryReservationRecord reservation = reservationRepository.lock(id).orElseThrow(this::notFound);
                if (reservation.status() == ReservationStatus.ACTIVE && !reservation.expiresAt().isAfter(now)) {
                    transitionActive(
                            reservation,
                            ReservationStatus.EXPIRED,
                            ReservationReleaseReason.EXPIRY.name(),
                            "EXPIRED",
                            "inventory.reservation.expired.v1",
                            "inventory-expiry-worker",
                            now);
                    count++;
                }
            }
            return count;
        });
        int count = expired == null ? 0 : expired;
        metrics.expired(count);
        if (count > 0) {
            log.info("Expired inventory reservations count={}", count);
        }
        return count;
    }

    private CommandOutcome reserveTransaction(
            ValidatedReserve request,
            String callerScope,
            String key,
            String requestHash,
            String correlationId,
            Instant now) {
        CommandOutcome replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            return replay;
        }
        InventoryReservationRecord existing =
                reservationRepository.findByCheckoutAndPurpose(request.checkoutId(), request.purpose()).orElse(null);
        if (existing != null) {
            return storeFailure(
                    callerScope,
                    key,
                    requestHash,
                    "RESERVE",
                    existing.id(),
                    HttpStatus.CONFLICT,
                    "INVENTORY_RESERVATION_ALREADY_EXISTS",
                    "This checkout already has an inventory reservation.",
                    now);
        }

        Map<String, InventoryItemRecord> locked = inventoryRepository.lockItemsByListingIds(
                request.items().stream().map(ValidatedItem::listingId).toList());
        for (ValidatedItem requested : request.items()) {
            InventoryItemRecord item = locked.get(requested.listingId());
            int available = item == null ? 0 : item.onHand() - item.reserved();
            if (item == null || available < requested.quantity()) {
                return storeFailure(
                        callerScope,
                        key,
                        requestHash,
                        "RESERVE",
                        null,
                        HttpStatus.CONFLICT,
                        "INVENTORY_INSUFFICIENT_STOCK",
                        "Requested inventory is not currently available for listing " + requested.listingId() + ".",
                        now);
            }
        }

        String reservationId = ulidGenerator.next();
        InventoryReservationRecord reservation = new InventoryReservationRecord(
                reservationId,
                request.checkoutId(),
                request.purpose(),
                ReservationStatus.ACTIVE,
                request.expiresAt(),
                null,
                null,
                null,
                0,
                now,
                now);
        reservationRepository.insertReservation(reservation);

        List<InventoryReservationItemRecord> lines = new ArrayList<>();
        Map<String, InventoryItemRecord> changedById = new LinkedHashMap<>();
        for (ValidatedItem requested : request.items()) {
            InventoryItemRecord item = locked.get(requested.listingId());
            int nextReserved = Math.addExact(item.reserved(), requested.quantity());
            requireBalanceUpdate(item, item.onHand(), nextReserved, now);
            String lineId = ulidGenerator.next();
            reservationRepository.insertItem(
                    lineId,
                    reservationId,
                    item,
                    requested.quantity(),
                    nextReserved,
                    item.version() + 1,
                    now);
            lines.add(new InventoryReservationItemRecord(
                    lineId,
                    reservationId,
                    item.id(),
                    item.businessId(),
                    item.listingId(),
                    requested.quantity(),
                    now,
                    now));
            changedById.put(item.id(), changed(item, item.onHand(), nextReserved, now));
        }

        String commandId = ulidGenerator.next();
        reservationRepository.insertHistory(
                ulidGenerator.next(),
                reservation.id(),
                reservation.checkoutId(),
                "CREATED",
                null,
                ReservationStatus.ACTIVE.name(),
                request.purpose().name(),
                commandId,
                null,
                correlationId,
                now);
        InventoryReservationResponse response = response(reservation, lines, changedById, now);
        inventoryRepository.insertOutbox(
                ulidGenerator.next(),
                "INVENTORY_RESERVATION",
                reservation.id(),
                "inventory.reservation.created.v1",
                eventPayload(response, commandId),
                correlationId,
                commandId,
                now);
        storeSuccess(callerScope, key, requestHash, "RESERVE", response, HttpStatus.CREATED, commandId, now);
        log.info("Reserved inventory reservationId={} checkoutId={} itemCount={}",
                reservation.id(), reservation.checkoutId(), lines.size());
        return CommandOutcome.success(response);
    }

    private CommandOutcome releaseTransaction(
            String reservationId,
            ReservationReleaseReason requestedReason,
            String callerScope,
            String key,
            String requestHash,
            String correlationId,
            Instant now) {
        CommandOutcome replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            return replay;
        }
        InventoryReservationRecord reservation = reservationRepository.lock(reservationId).orElse(null);
        if (reservation == null) {
            return storeFailure(callerScope, key, requestHash, "RELEASE", null, HttpStatus.NOT_FOUND,
                    "INVENTORY_RESERVATION_NOT_FOUND", "Inventory reservation was not found.", now);
        }
        if (reservation.status() == ReservationStatus.COMMITTED) {
            return storeFailure(callerScope, key, requestHash, "RELEASE", reservation.id(), HttpStatus.CONFLICT,
                    "INVENTORY_RESERVATION_ALREADY_COMMITTED",
                    "A committed reservation cannot be released.", now);
        }
        if (reservation.status() == ReservationStatus.RELEASED
                || reservation.status() == ReservationStatus.EXPIRED) {
            InventoryReservationResponse response =
                    response(reservation, reservationRepository.findItems(reservation.id()), now);
            storeSuccess(callerScope, key, requestHash, "RELEASE", response, HttpStatus.OK,
                    ulidGenerator.next(), now);
            return CommandOutcome.success(response);
        }

        boolean expired = !reservation.expiresAt().isAfter(now);
        ReservationStatus nextStatus = expired ? ReservationStatus.EXPIRED : ReservationStatus.RELEASED;
        String reason = expired ? ReservationReleaseReason.EXPIRY.name() : requestedReason.name();
        InventoryReservationResponse response = transitionActive(
                reservation,
                nextStatus,
                reason,
                nextStatus.name(),
                expired ? "inventory.reservation.expired.v1" : "inventory.reservation.released.v1",
                correlationId,
                now);
        storeSuccess(callerScope, key, requestHash, "RELEASE", response, HttpStatus.OK,
                ulidGenerator.next(), now);
        return CommandOutcome.success(response);
    }

    private CommandOutcome commitTransaction(
            String reservationId,
            String callerScope,
            String key,
            String requestHash,
            String correlationId,
            Instant now) {
        CommandOutcome replay = replay(callerScope, key, requestHash);
        if (replay != null) {
            return replay;
        }
        InventoryReservationRecord reservation = reservationRepository.lock(reservationId).orElse(null);
        if (reservation == null) {
            return storeFailure(callerScope, key, requestHash, "COMMIT", null, HttpStatus.NOT_FOUND,
                    "INVENTORY_RESERVATION_NOT_FOUND", "Inventory reservation was not found.", now);
        }
        if (reservation.status() == ReservationStatus.COMMITTED) {
            InventoryReservationResponse response =
                    response(reservation, reservationRepository.findItems(reservation.id()), now);
            storeSuccess(callerScope, key, requestHash, "COMMIT", response, HttpStatus.OK,
                    ulidGenerator.next(), now);
            return CommandOutcome.success(response);
        }
        if (reservation.status() == ReservationStatus.RELEASED
                || reservation.status() == ReservationStatus.EXPIRED) {
            signalCommitRejected(reservation, correlationId, now);
            String code = reservation.status() == ReservationStatus.EXPIRED
                    ? "INVENTORY_RESERVATION_EXPIRED"
                    : "INVENTORY_RESERVATION_NOT_COMMITTABLE";
            return storeFailure(callerScope, key, requestHash, "COMMIT", reservation.id(), HttpStatus.CONFLICT,
                    code, "Reservation cannot be committed; inventory recovery is required.", now);
        }
        if (!reservation.expiresAt().isAfter(now)) {
            InventoryReservationResponse expired = transitionActive(
                    reservation,
                    ReservationStatus.EXPIRED,
                    ReservationReleaseReason.EXPIRY.name(),
                    "EXPIRED",
                    "inventory.reservation.expired.v1",
                    correlationId,
                    now);
            InventoryReservationRecord expiredRecord = new InventoryReservationRecord(
                    expired.id(),
                    expired.checkoutId(),
                    expired.purpose(),
                    expired.status(),
                    expired.expiresAt(),
                    expired.committedAt(),
                    expired.releasedAt(),
                    expired.releaseReason(),
                    expired.version(),
                    expired.createdAt(),
                    expired.updatedAt());
            signalCommitRejected(expiredRecord, correlationId, now);
            return storeFailure(callerScope, key, requestHash, "COMMIT", reservation.id(), HttpStatus.CONFLICT,
                    "INVENTORY_RESERVATION_EXPIRED",
                    "Reservation expired before commit; inventory recovery is required.", now);
        }

        List<InventoryReservationItemRecord> lines = reservationRepository.findItems(reservation.id());
        Map<String, InventoryItemRecord> locked = inventoryRepository.lockItemsByListingIds(
                lines.stream().map(InventoryReservationItemRecord::listingId).toList());
        Map<String, InventoryItemRecord> changedById = new LinkedHashMap<>();
        for (InventoryReservationItemRecord line : lines) {
            InventoryItemRecord item = requireLockedItem(locked, line);
            int nextOnHand = Math.subtractExact(item.onHand(), line.quantity());
            int nextReserved = Math.subtractExact(item.reserved(), line.quantity());
            requireBalanceUpdate(item, nextOnHand, nextReserved, now);
            reservationRepository.updateItemTerminalSnapshot(
                    line.id(), nextOnHand, nextReserved, item.version() + 1, now);
            changedById.put(item.id(), changed(item, nextOnHand, nextReserved, now));
        }
        if (reservationRepository.transition(reservation.id(), ReservationStatus.COMMITTED, null, now) != 1) {
            throw invariant("Reservation commit lost its active state.");
        }
        InventoryReservationRecord committed = terminalRecord(
                reservation, ReservationStatus.COMMITTED, null, now);
        String commandId = ulidGenerator.next();
        reservationRepository.insertHistory(
                ulidGenerator.next(),
                reservation.id(),
                reservation.checkoutId(),
                "COMMITTED",
                ReservationStatus.ACTIVE.name(),
                ReservationStatus.COMMITTED.name(),
                "ORDER_COMMIT",
                commandId,
                null,
                correlationId,
                now);
        InventoryReservationResponse response = response(committed, lines, changedById, now);
        inventoryRepository.insertOutbox(
                ulidGenerator.next(),
                "INVENTORY_RESERVATION",
                reservation.id(),
                "inventory.reservation.committed.v1",
                eventPayload(response, commandId),
                correlationId,
                commandId,
                now);
        storeSuccess(callerScope, key, requestHash, "COMMIT", response, HttpStatus.OK, commandId, now);
        log.info("Committed inventory reservation reservationId={} checkoutId={}",
                reservation.id(), reservation.checkoutId());
        return CommandOutcome.success(response);
    }

    private InventoryReservationResponse transitionActive(
            InventoryReservationRecord reservation,
            ReservationStatus nextStatus,
            String reason,
            String eventType,
            String outboxType,
            String correlationId,
            Instant now) {
        List<InventoryReservationItemRecord> lines = reservationRepository.findItems(reservation.id());
        Map<String, InventoryItemRecord> locked = inventoryRepository.lockItemsByListingIds(
                lines.stream().map(InventoryReservationItemRecord::listingId).toList());
        Map<String, InventoryItemRecord> changedById = new LinkedHashMap<>();
        for (InventoryReservationItemRecord line : lines) {
            InventoryItemRecord item = requireLockedItem(locked, line);
            int nextReserved = Math.subtractExact(item.reserved(), line.quantity());
            requireBalanceUpdate(item, item.onHand(), nextReserved, now);
            reservationRepository.updateItemTerminalSnapshot(
                    line.id(), item.onHand(), nextReserved, item.version() + 1, now);
            changedById.put(item.id(), changed(item, item.onHand(), nextReserved, now));
        }
        if (reservationRepository.transition(reservation.id(), nextStatus, reason, now) != 1) {
            throw invariant("Reservation terminal transition lost its active state.");
        }
        InventoryReservationRecord terminal = terminalRecord(reservation, nextStatus, reason, now);
        String commandId = ulidGenerator.next();
        reservationRepository.insertHistory(
                ulidGenerator.next(),
                reservation.id(),
                reservation.checkoutId(),
                eventType,
                ReservationStatus.ACTIVE.name(),
                nextStatus.name(),
                reason,
                commandId,
                null,
                correlationId,
                now);
        InventoryReservationResponse response = response(terminal, lines, changedById, now);
        inventoryRepository.insertOutbox(
                ulidGenerator.next(),
                "INVENTORY_RESERVATION",
                reservation.id(),
                outboxType,
                eventPayload(response, commandId),
                correlationId,
                commandId,
                now);
        log.info("Transitioned inventory reservation reservationId={} status={} reason={}",
                reservation.id(), nextStatus, reason);
        return response;
    }

    private void signalCommitRejected(
            InventoryReservationRecord reservation,
            String correlationId,
            Instant now) {
        String deduplicationKey = "COMMIT_REJECTED:" + reservation.id() + ":" + reservation.status();
        if (reservationRepository.hasHistoryDeduplicationKey(deduplicationKey)) {
            return;
        }
        String commandId = ulidGenerator.next();
        reservationRepository.insertHistory(
                ulidGenerator.next(),
                reservation.id(),
                reservation.checkoutId(),
                "COMMIT_REJECTED",
                reservation.status().name(),
                null,
                "RECOVERY_REQUIRED",
                commandId,
                deduplicationKey,
                correlationId,
                now);
        inventoryRepository.insertOutbox(
                ulidGenerator.next(),
                "INVENTORY_RESERVATION",
                reservation.id(),
                "inventory.reservation.commit-rejected.v1",
                json(Map.of(
                        "reservationId", reservation.id(),
                        "checkoutId", reservation.checkoutId(),
                        "status", reservation.status().name(),
                        "recoveryRequired", true,
                        "occurredAt", now.toString())),
                correlationId,
                commandId,
                now);
        metrics.recoveryRequired();
        log.error("Inventory reservation commit rejected reservationId={} status={} recoveryRequired=true",
                reservation.id(), reservation.status());
    }

    private InventoryReservationResponse response(
            InventoryReservationRecord reservation,
            List<InventoryReservationItemRecord> lines,
            Instant now) {
        Map<String, InventoryItemRecord> inventory = inventoryRepository.findItemsByIds(
                lines.stream().map(InventoryReservationItemRecord::inventoryItemId).toList());
        return response(reservation, lines, inventory, now);
    }

    private InventoryReservationResponse response(
            InventoryReservationRecord reservation,
            List<InventoryReservationItemRecord> lines,
            Map<String, InventoryItemRecord> inventory,
            Instant now) {
        List<InventoryReservationItemResponse> items = lines.stream()
                .sorted(Comparator.comparing(InventoryReservationItemRecord::listingId))
                .map(line -> {
                    InventoryItemRecord item = inventory.get(line.inventoryItemId());
                    if (item == null) {
                        throw invariant("Reservation references missing inventory.");
                    }
                    return new InventoryReservationItemResponse(
                            item.id(),
                            line.businessId(),
                            line.listingId(),
                            line.quantity(),
                            item.onHand(),
                            item.reserved(),
                            item.onHand() - item.reserved(),
                            item.version());
                })
                .toList();
        return new InventoryReservationResponse(
                reservation.id(),
                reservation.checkoutId(),
                reservation.purpose(),
                reservation.status(),
                reservation.status() == ReservationStatus.ACTIVE && reservation.expiresAt().isAfter(now),
                reservation.expiresAt(),
                reservation.committedAt(),
                reservation.releasedAt(),
                reservation.releaseReason(),
                reservation.version(),
                reservation.createdAt(),
                reservation.updatedAt(),
                items);
    }

    private CommandOutcome replay(String callerScope, String key, String requestHash) {
        IdempotencyRecord record = inventoryRepository.findIdempotency(callerScope, key).orElse(null);
        if (record == null) {
            return null;
        }
        if (!record.requestHash().equals(requestHash)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used for another request.");
        }
        try {
            if (record.httpStatus() >= 200 && record.httpStatus() < 300) {
                return CommandOutcome.success(
                        objectMapper.readValue(record.responseJson(), InventoryReservationResponse.class));
            }
            StoredError error = objectMapper.readValue(record.responseJson(), StoredError.class);
            return CommandOutcome.failure(
                    HttpStatus.valueOf(record.httpStatus()), error.code(), error.message());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored reservation result could not be read.", exception);
        }
    }

    private void storeSuccess(
            String callerScope,
            String key,
            String requestHash,
            String operation,
            InventoryReservationResponse response,
            HttpStatus status,
            String commandId,
            Instant now) {
        inventoryRepository.insertIdempotency(
                commandId,
                callerScope,
                key,
                requestHash,
                operation,
                response.id(),
                status.value(),
                json(response),
                now);
        metrics.command(operation, "SUCCESS");
    }

    private CommandOutcome storeFailure(
            String callerScope,
            String key,
            String requestHash,
            String operation,
            String resultResourceId,
            HttpStatus status,
            String code,
            String message,
            Instant now) {
        inventoryRepository.insertIdempotency(
                ulidGenerator.next(),
                callerScope,
                key,
                requestHash,
                operation,
                resultResourceId,
                status.value(),
                json(new StoredError(code, message)),
                now);
        metrics.command(operation, code);
        return CommandOutcome.failure(status, code, message);
    }

    private ValidatedReserve validateReserve(InventoryReservationRequest request) {
        if (request == null) {
            throw invalid("Reservation request is required.");
        }
        String checkoutId = requiredId("Checkout ID", request.checkoutId());
        ReservationPurpose purpose = request.purpose();
        if (purpose == null) {
            throw invalid("Reservation purpose is required.");
        }
        Instant expiresAt = request.expiresAt();
        if (expiresAt == null) {
            throw invalid("Reservation expiry is required.");
        }
        if (request.items() == null || request.items().isEmpty() || request.items().size() > MAX_ITEMS) {
            throw invalid("Reservation must contain from 1 through 50 items.");
        }
        Map<String, ValidatedItem> byListing = new LinkedHashMap<>();
        for (InventoryReservationRequest.Item item : request.items()) {
            if (item == null) {
                throw invalid("Reservation item is required.");
            }
            String listingId = requiredId("Listing ID", item.listingId());
            int quantity = item.quantity() == null ? 0 : item.quantity();
            if (quantity < 1 || quantity > MAX_QUANTITY) {
                throw invalid("Reservation quantity must be from 1 through 999.");
            }
            if (byListing.putIfAbsent(listingId, new ValidatedItem(listingId, quantity)) != null) {
                throw invalid("Reservation listing IDs must be distinct.");
            }
        }
        List<ValidatedItem> sorted = byListing.values().stream()
                .sorted(Comparator.comparing(ValidatedItem::listingId))
                .toList();
        return new ValidatedReserve(checkoutId, purpose, expiresAt, sorted);
    }

    private void requireNewReservationLifetime(ValidatedReserve request, Instant now) {
        if (!request.expiresAt().isAfter(now)) {
            throw invalid("Reservation expiry must be in the future.");
        }
        if (Duration.between(now, request.expiresAt()).compareTo(maxLifetime) > 0) {
            throw invalid("Reservation expiry exceeds the configured maximum lifetime.");
        }
    }

    private String reserveHash(ValidatedReserve request) {
        List<String> fields = new ArrayList<>();
        fields.add("RESERVE");
        fields.add(request.checkoutId());
        fields.add(request.purpose().name());
        fields.add(request.expiresAt().toString());
        request.items().forEach(item -> {
            fields.add(item.listingId());
            fields.add(Integer.toString(item.quantity()));
        });
        return hash(fields.toArray(String[]::new));
    }

    private void requireBalanceUpdate(
            InventoryItemRecord item,
            int nextOnHand,
            int nextReserved,
            Instant now) {
        if (nextOnHand < 0 || nextReserved < 0 || nextReserved > nextOnHand) {
            throw invariant("Inventory reservation would violate stock balances.");
        }
        int updated = inventoryRepository.updateBalances(
                item.id(), item.version(), nextOnHand, nextReserved, now);
        if (updated != 1) {
            throw conflict(
                    "INVENTORY_RESERVATION_CONFLICT",
                    "Inventory changed while the reservation command was running.");
        }
    }

    private InventoryItemRecord requireLockedItem(
            Map<String, InventoryItemRecord> locked,
            InventoryReservationItemRecord line) {
        InventoryItemRecord item = locked.get(line.listingId());
        if (item == null || !item.id().equals(line.inventoryItemId())) {
            throw invariant("Reservation inventory ownership no longer matches.");
        }
        return item;
    }

    private InventoryItemRecord changed(
            InventoryItemRecord current,
            int onHand,
            int reserved,
            Instant now) {
        return new InventoryItemRecord(
                current.id(),
                current.businessId(),
                current.listingId(),
                current.skuSnapshot(),
                current.catalogVersionSnapshot(),
                onHand,
                reserved,
                current.version() + 1,
                current.initializedAt(),
                current.createdAt(),
                now);
    }

    private InventoryReservationRecord terminalRecord(
            InventoryReservationRecord current,
            ReservationStatus status,
            String reason,
            Instant now) {
        return new InventoryReservationRecord(
                current.id(),
                current.checkoutId(),
                current.purpose(),
                status,
                current.expiresAt(),
                status == ReservationStatus.COMMITTED ? now : null,
                status == ReservationStatus.RELEASED || status == ReservationStatus.EXPIRED ? now : null,
                reason,
                current.version() + 1,
                current.createdAt(),
                now);
    }

    private String eventPayload(InventoryReservationResponse response, String commandId) {
        return json(Map.of(
                "commandId", commandId,
                "reservationId", response.id(),
                "checkoutId", response.checkoutId(),
                "purpose", response.purpose().name(),
                "status", response.status().name(),
                "expiresAt", response.expiresAt().toString(),
                "occurredAt", response.updatedAt().toString(),
                "items", response.items().stream().map(item -> Map.of(
                        "businessId", item.businessId(),
                        "listingId", item.listingId(),
                        "quantity", item.quantity(),
                        "onHand", item.onHand(),
                        "reserved", item.reserved(),
                        "available", item.available(),
                        "inventoryVersion", item.inventoryVersion())).toList()));
    }

    private String requiredId(String fieldName, String value) {
        try {
            return FixedLengthIds.requireTrimmed(fieldName, value, 26);
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
    }

    private String requiredIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be a safe value.");
        }
        return value.trim();
    }

    private String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Reservation result could not be serialized.", exception);
        }
    }

    private InventoryException invalid(String message) {
        return new InventoryException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVENTORY_INVALID_RESERVATION",
                message);
    }

    private InventoryException conflict(String code, String message) {
        return new InventoryException(HttpStatus.CONFLICT, code, message);
    }

    private InventoryException notFound() {
        return new InventoryException(
                HttpStatus.NOT_FOUND,
                "INVENTORY_RESERVATION_NOT_FOUND",
                "Inventory reservation was not found.");
    }

    private IllegalStateException invariant(String message) {
        log.error("Inventory reservation invariant failure: {}", message);
        return new IllegalStateException(message);
    }

    private record ValidatedItem(String listingId, int quantity) {
    }

    private record ValidatedReserve(
            String checkoutId,
            ReservationPurpose purpose,
            Instant expiresAt,
            List<ValidatedItem> items) {
    }

    private record StoredError(String code, String message) {
    }

    private record CommandOutcome(
            InventoryReservationResponse response,
            HttpStatus errorStatus,
            String errorCode,
            String errorMessage) {

        static CommandOutcome success(InventoryReservationResponse response) {
            return new CommandOutcome(response, null, null, null);
        }

        static CommandOutcome failure(HttpStatus status, String code, String message) {
            return new CommandOutcome(null, status, code, message);
        }

        InventoryReservationResponse orThrow() {
            if (response != null) {
                return response;
            }
            throw new InventoryException(errorStatus, errorCode, errorMessage);
        }
    }
}
