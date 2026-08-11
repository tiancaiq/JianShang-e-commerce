package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.dto.CheckoutResponse;
import com.msb.ecom.order_service.dto.CreateCheckoutRequest;
import com.msb.ecom.order_service.model.CartAssessment;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.repository.CartRepository;
import com.msb.ecom.order_service.repository.CheckoutIdempotencyRecord;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");

    private final CurrentActorProvider actorProvider;
    private final CartRepository cartRepository;
    private final CartAssessmentService assessmentService;
    private final BuyerIdentityClient buyerIdentityClient;
    private final CheckoutCalculationService calculationService;
    private final InventoryReservationClient inventoryClient;
    private final CheckoutRepository repository;
    private final CheckoutProperties properties;
    private final CheckoutUlidGenerator ids;
    private final CheckoutMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public CheckoutService(
            CurrentActorProvider actorProvider,
            CartRepository cartRepository,
            CartAssessmentService assessmentService,
            BuyerIdentityClient buyerIdentityClient,
            CheckoutCalculationService calculationService,
            InventoryReservationClient inventoryClient,
            CheckoutRepository repository,
            CheckoutProperties properties,
            CheckoutUlidGenerator ids,
            CheckoutMetrics metrics,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                actorProvider,
                cartRepository,
                assessmentService,
                buyerIdentityClient,
                calculationService,
                inventoryClient,
                repository,
                properties,
                ids,
                metrics,
                objectMapper,
                transactionManager,
                Clock.systemUTC());
    }

    CheckoutService(
            CurrentActorProvider actorProvider,
            CartRepository cartRepository,
            CartAssessmentService assessmentService,
            BuyerIdentityClient buyerIdentityClient,
            CheckoutCalculationService calculationService,
            InventoryReservationClient inventoryClient,
            CheckoutRepository repository,
            CheckoutProperties properties,
            CheckoutUlidGenerator ids,
            CheckoutMetrics metrics,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.actorProvider = actorProvider;
        this.cartRepository = cartRepository;
        this.assessmentService = assessmentService;
        this.buyerIdentityClient = buyerIdentityClient;
        this.calculationService = calculationService;
        this.inventoryClient = inventoryClient;
        this.repository = repository;
        this.properties = properties;
        this.ids = ids;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Persists one checkout snapshot before reserving stock and resumes ambiguous retries safely.
    public CheckoutResponse create(
            String idempotencyKey,
            CreateCheckoutRequest request,
            String correlationId) {
        requireEnabled();
        String key = requiredKey(idempotencyKey);
        if (request == null) {
            throw invalid("Checkout request is required.");
        }
        String subject = actorProvider.currentActor().subject();
        String buyerId = buyerIdentityClient.resolveBuyer(subject);
        String scope = createScope(buyerId);
        String requestHash = hash("CREATE", subject, Long.toString(request.cartVersion()), request.addressId());
        CheckoutResponse replay = replay(scope, key, requestHash);
        if (replay != null) {
            return replay;
        }
        CartDocument cart = cartRepository.get(subject);
        if (cart.items().isEmpty()) {
            throw conflict("CHECKOUT_CART_EMPTY", "Your cart is empty.");
        }
        if (cart.version() != request.cartVersion()) {
            throw conflict("CHECKOUT_CART_VERSION_CONFLICT", "Your cart changed. Review it and try again.");
        }
        CartAssessment assessment = assessmentService.assess(cart);
        if (!assessment.checkoutReady()) {
            throw conflict("CHECKOUT_CART_INVALID", "Your cart needs attention before checkout.");
        }
        BuyerIdentityClient.BuyerAddress address =
                buyerIdentityClient.resolveAddress(subject, request.addressId());
        if (!buyerId.equals(address.buyerId())) {
            metrics.invariant();
            throw notFound();
        }
        CheckoutAggregate active = repository.findActiveByBuyer(buyerId).orElse(null);
        if (active != null) {
            if (active.status() == CheckoutStatus.RESERVING) {
                metrics.command("CREATE", "RESERVATION_RECONCILE");
                return reserve(active, null, null, correlationId);
            }
            metrics.command("CREATE", "ALREADY_ACTIVE");
            throw new CheckoutException(
                    HttpStatus.CONFLICT,
                    "CHECKOUT_ALREADY_ACTIVE",
                    "You already have an active checkout.",
                    active.id());
        }

        String checkoutId = ids.next();
        CheckoutAggregate checkout;
        try {
            checkout = calculationService.calculate(checkoutId, buyerId, assessment, address);
        } catch (CheckoutException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            metrics.invariant();
            throw new CheckoutException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CHECKOUT_CALCULATION_INVALID",
                    "Checkout totals could not be calculated.");
        }

        try {
            transactions.executeWithoutResult(status -> {
                repository.insert(checkout);
                repository.insertCartReconciliation(
                        checkout.id(),
                        subject,
                        checkout.cartVersion(),
                        assessment.cart().items(),
                        checkout.createdAt());
                repository.insertIdempotency(
                        ids.next(),
                        scope,
                        key,
                        requestHash,
                        "CREATE_CHECKOUT",
                        checkout.id(),
                        checkout.createdAt(),
                        checkout.createdAt().plus(properties.idempotencyRetention()));
                String commandId = ids.next();
                repository.history(
                        ids.next(),
                        checkout.id(),
                        null,
                        CheckoutStatus.RESERVING.name(),
                        "CHECKOUT_CREATED",
                        commandId,
                        correlationId,
                        checkout.createdAt());
            });
        } catch (DuplicateKeyException exception) {
            CheckoutResponse concurrentReplay = replay(scope, key, requestHash);
            if (concurrentReplay != null) {
                return concurrentReplay;
            }
            CheckoutAggregate concurrentActive = repository.findActiveByBuyer(buyerId).orElse(null);
            if (concurrentActive != null) {
                throw new CheckoutException(
                        HttpStatus.CONFLICT,
                        "CHECKOUT_ALREADY_ACTIVE",
                        "You already have an active checkout.",
                        concurrentActive.id());
            }
            throw exception;
        }

        return reserve(checkout, scope, key, correlationId);
    }

    public CheckoutResponse get(String checkoutId) {
        requireEnabled();
        String buyerId = buyerIdentityClient.resolveBuyer(actorProvider.currentActor().subject());
        return response(repository.findOwned(checkoutId, buyerId)
                .orElseThrow(this::notFound));
    }

    // Cancels only an unpaid buyer-owned checkout and reconciles release outside the transaction.
    public CheckoutResponse cancel(String checkoutId, String idempotencyKey, String correlationId) {
        requireEnabled();
        String key = requiredKey(idempotencyKey);
        String buyerId = buyerIdentityClient.resolveBuyer(actorProvider.currentActor().subject());
        String scope = cancelScope(buyerId, checkoutId);
        String requestHash = hash("CANCEL", buyerId, checkoutId);
        CheckoutResponse replay = replay(scope, key, requestHash);
        if (replay != null) {
            return replay;
        }

        CheckoutAggregate cancelled = Objects.requireNonNull(transactions.execute(status -> {
            CheckoutAggregate current = repository.lockOwned(checkoutId, buyerId)
                    .orElseThrow(this::notFound);
            if (current.status() != CheckoutStatus.PENDING_PAYMENT
                    && current.status() != CheckoutStatus.CANCELLED) {
                throw conflict("CHECKOUT_NOT_CANCELLABLE", "This checkout cannot be cancelled.");
            }
            Instant now = clock.instant();
            if (current.status() == CheckoutStatus.PENDING_PAYMENT) {
                repository.cancel(current.id(), now);
                String commandId = ids.next();
                repository.history(
                        ids.next(),
                        current.id(),
                        CheckoutStatus.PENDING_PAYMENT.name(),
                        CheckoutStatus.CANCELLED.name(),
                        "BUYER_CANCELLED",
                        commandId,
                        correlationId,
                        now);
                repository.outbox(
                        ids.next(),
                        current.id(),
                        "checkout.cancelled.v1",
                        eventJson(current, CheckoutStatus.CANCELLED),
                        correlationId,
                        commandId,
                        now);
            }
            CheckoutAggregate result = repository.find(current.id()).orElseThrow(this::notFound);
            repository.insertIdempotency(
                    ids.next(),
                    scope,
                    key,
                    requestHash,
                    "CANCEL_CHECKOUT",
                    result.id(),
                    now,
                    now.plus(properties.idempotencyRetention()));
            repository.completeIdempotency(scope, key, 200, json(response(result)), now);
            return result;
        }));
        reconcileRelease(cancelled);
        metrics.command("CANCEL", "SUCCESS");
        return response(repository.find(cancelled.id()).orElse(cancelled));
    }

    // Replays deterministic inventory commands for durable checkouts left in RESERVING.
    public int recoverReserving() {
        if (!properties.enabled()) {
            return 0;
        }
        int recovered = 0;
        for (String id : repository.reservingIds(properties.recoveryBatchSize())) {
            CheckoutAggregate checkout = repository.find(id).orElse(null);
            if (checkout == null || checkout.status() != CheckoutStatus.RESERVING) {
                continue;
            }
            if (!checkout.expiresAt().isAfter(clock.instant())) {
                expireOne(checkout.id(), "RESERVATION_DEADLINE_REACHED");
                continue;
            }
            try {
                completeReservation(checkout, inventoryClient.reserve(
                        checkout.id(),
                        checkout.expiresAt(),
                        reservationLines(checkout)), null, null, "checkout-recovery-worker");
                recovered++;
            } catch (CheckoutException exception) {
                if ("CHECKOUT_INSUFFICIENT_STOCK".equals(exception.code())) {
                    failCheckout(checkout, exception.code(), null, null, "checkout-recovery-worker");
                }
            }
        }
        return recovered;
    }

    // Expires a bounded locked batch without calling inventory under the row transaction.
    public int expireDue() {
        if (!properties.enabled() || !properties.expiryWorkerEnabled()) {
            return 0;
        }
        Integer count = transactions.execute(status -> {
            int expired = 0;
            for (String id : repository.lockDueExpiryIds(clock.instant(), properties.expiryBatchSize())) {
                CheckoutAggregate checkout = repository.lock(id).orElse(null);
                if (checkout != null
                        && (checkout.status() == CheckoutStatus.PENDING_PAYMENT
                        || checkout.status() == CheckoutStatus.RESERVING)
                        && !checkout.expiresAt().isAfter(clock.instant())) {
                    expireLocked(checkout, "CHECKOUT_EXPIRED", "checkout-expiry-worker");
                    expired++;
                }
            }
            return expired;
        });
        int result = count == null ? 0 : count;
        metrics.expired(result);
        return result;
    }

    public int reconcilePendingReleases() {
        if (!properties.enabled()) {
            return 0;
        }
        int completed = 0;
        for (String id : repository.releasePendingIds(properties.recoveryBatchSize())) {
            CheckoutAggregate checkout = repository.find(id).orElse(null);
            if (checkout != null && reconcileRelease(checkout)) {
                completed++;
            }
        }
        return completed;
    }

    private CheckoutResponse reserve(
            CheckoutAggregate checkout,
            String scope,
            String key,
            String correlationId) {
        try {
            InventoryReservationClient.Reservation reservation = inventoryClient.reserve(
                    checkout.id(),
                    checkout.expiresAt(),
                    reservationLines(checkout));
            return completeReservation(checkout, reservation, scope, key, correlationId);
        } catch (CheckoutException exception) {
            if ("CHECKOUT_INSUFFICIENT_STOCK".equals(exception.code())) {
                failCheckout(checkout, exception.code(), scope, key, correlationId);
            } else {
                metrics.reservation("PENDING");
            }
            throw exception;
        }
    }

    private CheckoutResponse completeReservation(
            CheckoutAggregate checkout,
            InventoryReservationClient.Reservation reservation,
            String scope,
            String key,
            String correlationId) {
        verifyReservation(checkout, reservation);
        InventoryReservationClient.Reservation observed = inventoryClient.get(reservation.id());
        verifyReservation(checkout, observed);
        if (!"ACTIVE".equals(observed.status())
                || !observed.usable()
                || !observed.expiresAt().isAfter(clock.instant())) {
            expireOne(checkout.id(), "RESERVATION_NOT_USABLE");
            throw conflict("CHECKOUT_NOT_CANCELLABLE", "Checkout reservation has expired.");
        }
        CheckoutResponse result = Objects.requireNonNull(transactions.execute(status -> {
            CheckoutAggregate locked = repository.lock(checkout.id()).orElseThrow(this::notFound);
            if (locked.status() == CheckoutStatus.PENDING_PAYMENT) {
                return response(locked);
            }
            if (locked.status() != CheckoutStatus.RESERVING) {
                throw new IllegalStateException("Checkout cannot accept a reservation in its current state.");
            }
            Instant now = clock.instant();
            if (repository.markPending(
                    checkout.id(),
                    observed.id(),
                    observed.status(),
                    observed.version(),
                    now) != 1) {
                throw new IllegalStateException("Checkout reservation transition was lost.");
            }
            String commandId = ids.next();
            repository.history(
                    ids.next(),
                    checkout.id(),
                    CheckoutStatus.RESERVING.name(),
                    CheckoutStatus.PENDING_PAYMENT.name(),
                    "INVENTORY_RESERVED",
                    commandId,
                    correlationId,
                    now);
            CheckoutAggregate pending = repository.find(checkout.id()).orElseThrow(this::notFound);
            CheckoutResponse pendingResponse = response(pending);
            repository.outbox(
                    ids.next(),
                    checkout.id(),
                    "checkout.created.v1",
                    eventJson(pending, CheckoutStatus.PENDING_PAYMENT),
                    correlationId,
                    commandId,
                    now);
            if (scope != null) {
                repository.completeIdempotency(scope, key, 201, json(pendingResponse), now);
            }
            return pendingResponse;
        }));
        metrics.reservation("SUCCESS");
        metrics.command("CREATE", "SUCCESS");
        log.info("Checkout ready checkoutId={} itemCount={} expiresAt={}",
                checkout.id(), checkout.items().size(), checkout.expiresAt());
        return result;
    }

    private void failCheckout(
            CheckoutAggregate checkout,
            String failureCode,
            String scope,
            String key,
            String correlationId) {
        transactions.executeWithoutResult(status -> {
            CheckoutAggregate locked = repository.lock(checkout.id()).orElseThrow(this::notFound);
            if (locked.status() != CheckoutStatus.RESERVING) {
                return;
            }
            Instant now = clock.instant();
            repository.markFailed(checkout.id(), failureCode, now);
            String commandId = ids.next();
            repository.history(
                    ids.next(),
                    checkout.id(),
                    CheckoutStatus.RESERVING.name(),
                    CheckoutStatus.FAILED.name(),
                    failureCode,
                    commandId,
                    correlationId,
                    now);
            CheckoutAggregate failed = repository.find(checkout.id()).orElseThrow(this::notFound);
            repository.outbox(
                    ids.next(),
                    checkout.id(),
                    "checkout.failed.v1",
                    eventJson(failed, CheckoutStatus.FAILED),
                    correlationId,
                    commandId,
                    now);
            if (scope != null) {
                repository.completeIdempotency(
                        scope,
                        key,
                        409,
                        json(new StoredError(failureCode, "One or more items no longer have enough stock.", checkout.id())),
                        now);
            }
        });
        metrics.reservation("INSUFFICIENT_STOCK");
    }

    private void verifyReservation(
            CheckoutAggregate checkout,
            InventoryReservationClient.Reservation reservation) {
        List<String> expected = checkout.items().stream()
                .map(item -> item.listingId() + ":" + item.quantity())
                .sorted()
                .toList();
        List<String> actual = reservation.items().stream()
                .map(item -> item.listingId() + ":" + item.quantity())
                .sorted()
                .toList();
        if (!checkout.id().equals(reservation.checkoutId())
                || !"CHECKOUT".equals(reservation.purpose())
                || !checkout.expiresAt().equals(reservation.expiresAt())
                || !expected.equals(actual)) {
            metrics.invariant();
            log.warn("Inventory reservation invariant mismatch checkoutId={} observedCheckoutId={} purpose={} expectedExpiresAt={} observedExpiresAt={} expectedItems={} observedItems={}",
                    checkout.id(), reservation.checkoutId(), reservation.purpose(), checkout.expiresAt(),
                    reservation.expiresAt(), expected, actual);
            throw new CheckoutException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CHECKOUT_RESERVATION_PENDING",
                    "Inventory reservation is still being reconciled.");
        }
    }

    private List<InventoryReservationClient.Line> reservationLines(CheckoutAggregate checkout) {
        return checkout.items().stream()
                .map(item -> new InventoryReservationClient.Line(item.listingId(), item.quantity()))
                .toList();
    }

    private void expireOne(String checkoutId, String reason) {
        transactions.executeWithoutResult(status -> repository.lock(checkoutId)
                .filter(checkout -> checkout.status() == CheckoutStatus.RESERVING
                        || checkout.status() == CheckoutStatus.PENDING_PAYMENT)
                .ifPresent(checkout -> expireLocked(checkout, reason, "checkout-recovery-worker")));
    }

    private void expireLocked(CheckoutAggregate checkout, String reason, String correlationId) {
        Instant now = clock.instant();
        repository.expire(checkout.id(), now);
        String commandId = ids.next();
        repository.history(
                ids.next(),
                checkout.id(),
                checkout.status().name(),
                CheckoutStatus.EXPIRED.name(),
                reason,
                commandId,
                correlationId,
                now);
        CheckoutAggregate expired = repository.find(checkout.id()).orElseThrow(this::notFound);
        repository.outbox(
                ids.next(),
                checkout.id(),
                "checkout.expired.v1",
                eventJson(expired, CheckoutStatus.EXPIRED),
                correlationId,
                commandId,
                now);
    }

    private boolean reconcileRelease(CheckoutAggregate checkout) {
        if (checkout.reservationId() == null) {
            transactions.executeWithoutResult(status ->
                    repository.releaseComplete(checkout.id(), "NOT_CREATED", 0, clock.instant()));
            return true;
        }
        try {
            String reason = checkout.status() == CheckoutStatus.CANCELLED
                    ? "CHECKOUT_CANCELLED"
                    : "SYSTEM_RECOVERY";
            InventoryReservationClient.Reservation reservation = inventoryClient.release(
                    checkout.id(), checkout.reservationId(), reason);
            if ("RELEASED".equals(reservation.status()) || "EXPIRED".equals(reservation.status())) {
                transactions.executeWithoutResult(status -> repository.releaseComplete(
                        checkout.id(), reservation.status(), reservation.version(), clock.instant()));
                return true;
            }
        } catch (CheckoutException exception) {
            transactions.executeWithoutResult(status -> repository.releasePendingFailure(
                    checkout.id(), exception.code(), clock.instant()));
        }
        return false;
    }

    private CheckoutResponse replay(String scope, String key, String requestHash) {
        CheckoutIdempotencyRecord record = repository.idempotency(scope, key).orElse(null);
        if (record == null) {
            return null;
        }
        if (!record.requestHash().equals(requestHash)) {
            metrics.command(record.operation(), "IDEMPOTENCY_CONFLICT");
            throw conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was used for another request.");
        }
        if ("IN_PROGRESS".equals(record.state())) {
            CheckoutAggregate checkout = repository.find(record.checkoutId()).orElseThrow(this::notFound);
            return reserve(checkout, scope, key, "checkout-idempotency-retry");
        }
        try {
            if (record.httpStatus() != null && record.httpStatus() >= 200 && record.httpStatus() < 300) {
                metrics.command(record.operation(), "REPLAY");
                return objectMapper.readValue(record.responseJson(), CheckoutResponse.class);
            }
            StoredError error = objectMapper.readValue(record.responseJson(), StoredError.class);
            throw new CheckoutException(
                    HttpStatus.valueOf(record.httpStatus()),
                    error.code(),
                    error.message(),
                    error.checkoutId());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored checkout result could not be read.", exception);
        }
    }

    private CheckoutResponse response(CheckoutAggregate checkout) {
        return new CheckoutResponse(
                checkout.id(),
                checkout.status().name(),
                checkout.cartVersion(),
                checkout.currency(),
                checkout.subtotal(),
                checkout.shipping(),
                checkout.tax(),
                checkout.discount(),
                checkout.total(),
                checkout.expiresAt(),
                new CheckoutResponse.Reservation(
                        checkout.reservationId(),
                        checkout.reservationStatus(),
                        checkout.releaseStatus().name()),
                new CheckoutResponse.Address(
                        checkout.address().sourceAddressId(),
                        checkout.address().sourceVersion(),
                        checkout.address().label(),
                        checkout.address().recipientName(),
                        checkout.address().phone(),
                        checkout.address().line1(),
                        checkout.address().line2(),
                        checkout.address().city(),
                        checkout.address().region(),
                        checkout.address().postalCode(),
                        checkout.address().countryCode()),
                checkout.items().stream().map(item -> new CheckoutResponse.Item(
                        item.listingId(),
                        item.businessId(),
                        item.storeId(),
                        item.storeName(),
                        item.catalogVersion(),
                        item.title(),
                        item.sku(),
                        item.condition(),
                        item.thumbnailUrl(),
                        item.quantity(),
                        item.unitPrice(),
                        item.lineSubtotal(),
                        item.shippingAllocation(),
                        item.taxAllocation(),
                        item.discountAllocation(),
                        item.lineTotal(),
                        item.policyVersion())).toList(),
                checkout.shippingQuotes().stream().map(quote -> new CheckoutResponse.ShippingQuote(
                        quote.businessId(),
                        quote.storeId(),
                        quote.methodCode(),
                        quote.amount(),
                        quote.adapter())).toList(),
                new CheckoutResponse.TaxQuote(checkout.taxQuote().amount(), checkout.taxQuote().adapter()),
                checkout.policies().stream().map(policy -> new CheckoutResponse.Policy(
                        policy.businessId(),
                        policy.storeId(),
                        policy.source(),
                        policy.version(),
                        policy.shippingText(),
                        policy.cancellationText(),
                        policy.returnText())).toList(),
                checkout.failureCode(),
                checkout.createdAt(),
                checkout.updatedAt());
    }

    private String eventJson(CheckoutAggregate checkout, CheckoutStatus status) {
        return json(java.util.Map.ofEntries(
                java.util.Map.entry("checkoutId", checkout.id()),
                java.util.Map.entry("buyerId", checkout.buyerId()),
                java.util.Map.entry("status", status.name()),
                java.util.Map.entry("cartVersion", checkout.cartVersion()),
                java.util.Map.entry("cartSnapshotHash", checkout.cartSnapshotHash()),
                java.util.Map.entry("currency", checkout.currency()),
                java.util.Map.entry("subtotal", checkout.subtotal()),
                java.util.Map.entry("shipping", checkout.shipping()),
                java.util.Map.entry("tax", checkout.tax()),
                java.util.Map.entry("discount", checkout.discount()),
                java.util.Map.entry("total", checkout.total()),
                java.util.Map.entry("expiresAt", checkout.expiresAt()),
                java.util.Map.entry("businessIds", checkout.items().stream()
                        .map(CheckoutAggregate.Item::businessId).distinct().sorted().toList()),
                java.util.Map.entry("listingIds", checkout.items().stream()
                        .map(CheckoutAggregate.Item::listingId).sorted().toList())));
    }

    private String requiredKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
            throw new CheckoutException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required and must be a safe value.");
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
            throw new IllegalStateException("Checkout result could not be serialized.", exception);
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_NOT_AVAILABLE",
                    "Checkout is not available.");
        }
    }

    private CheckoutException invalid(String message) {
        return new CheckoutException(HttpStatus.BAD_REQUEST, "CHECKOUT_INVALID_REQUEST", message);
    }

    private CheckoutException conflict(String code, String message) {
        return new CheckoutException(HttpStatus.CONFLICT, code, message);
    }

    private CheckoutException notFound() {
        return new CheckoutException(
                HttpStatus.NOT_FOUND,
                "CHECKOUT_NOT_FOUND",
                "Checkout was not found.");
    }

    private String createScope(String buyerId) {
        return "BUYER:CREATE_CHECKOUT:" + buyerId;
    }

    private String cancelScope(String buyerId, String checkoutId) {
        return "BUYER:CANCEL_CHECKOUT:" + buyerId + ":" + checkoutId;
    }

    private record StoredError(String code, String message, String checkoutId) {
    }
}
