package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.config.OrderConfirmationProperties;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.OrderConfirmationResult;
import com.msb.ecom.order_service.model.PaymentEventEnvelope;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.OrderConfirmationRepository;
import com.msb.ecom.order_service.repository.ProcessedPaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class PaymentSucceededOrderConfirmationHandler {

    private static final Logger log =
            LoggerFactory.getLogger(PaymentSucceededOrderConfirmationHandler.class);
    private static final String RECOVERY_REQUIRED = "ORDER_CONFIRMATION_REQUIRES_RECOVERY";

    private final OrderConfirmationProperties properties;
    private final PaymentEventValidator validator;
    private final CheckoutRepository checkouts;
    private final CheckoutPaymentBindingRepository paymentBindings;
    private final OrderConfirmationRepository orders;
    private final InventoryReservationClient inventory;
    private final CheckoutUlidGenerator ids;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public PaymentSucceededOrderConfirmationHandler(
            OrderConfirmationProperties properties,
            PaymentEventValidator validator,
            CheckoutRepository checkouts,
            CheckoutPaymentBindingRepository paymentBindings,
            OrderConfirmationRepository orders,
            InventoryReservationClient inventory,
            CheckoutUlidGenerator ids,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                properties,
                validator,
                checkouts,
                paymentBindings,
                orders,
                inventory,
                ids,
                objectMapper,
                transactionManager,
                Clock.systemUTC());
    }

    PaymentSucceededOrderConfirmationHandler(
            OrderConfirmationProperties properties,
            PaymentEventValidator validator,
            CheckoutRepository checkouts,
            CheckoutPaymentBindingRepository paymentBindings,
            OrderConfirmationRepository orders,
            InventoryReservationClient inventory,
            CheckoutUlidGenerator ids,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.properties = properties;
        this.validator = validator;
        this.checkouts = checkouts;
        this.paymentBindings = paymentBindings;
        this.orders = orders;
        this.inventory = inventory;
        this.ids = ids;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    // Confirms one paid checkout through durable claim, inventory commit, and atomic order creation.
    public OrderConfirmationResult handle(PaymentEventEnvelope event) {
        if (!properties.enabled()) {
            throw new OrderConfirmationException(
                    "ORDER_CONFIRMATION_DISABLED",
                    "Order confirmation is not available.");
        }
        validator.validate(event);
        String payloadHash = payloadHash(event);
        Instant now = clock.instant();
        Claim claim = Objects.requireNonNull(transactions.execute(status ->
                claim(event, payloadHash, now)));
        if (!claim.continueProcessing()) {
            return claim.result();
        }

        InventoryReservationClient.Reservation reservation;
        try {
            reservation = inventory.commit(
                    event.checkoutId(),
                    claim.checkout().reservationId(),
                    event.correlationId());
            validateCommittedReservation(claim.checkout(), reservation);
        } catch (OrderConfirmationException exception) {
            if (RECOVERY_REQUIRED.equals(exception.code())) {
                return recoveryRequired(event, claim.claimToken(), now);
            }
            retry(event, claim.claimToken(), "ORDER_INVENTORY_COMMIT_PENDING", now);
            return new OrderConfirmationResult(
                    OrderConfirmationResult.Outcome.RETRY_REQUIRED,
                    null,
                    "ORDER_INVENTORY_COMMIT_PENDING");
        } catch (RuntimeException exception) {
            retry(event, claim.claimToken(), "ORDER_INVENTORY_COMMIT_PENDING", now);
            return new OrderConfirmationResult(
                    OrderConfirmationResult.Outcome.RETRY_REQUIRED,
                    null,
                    "ORDER_INVENTORY_COMMIT_PENDING");
        }

        try {
            return Objects.requireNonNull(transactions.execute(status ->
                    confirm(event, payloadHash, claim.claimToken(), reservation, clock.instant())));
        } catch (RuntimeException exception) {
            retry(event, claim.claimToken(), "ORDER_PERSISTENCE_RETRY_REQUIRED", clock.instant());
            log.warn(
                    "Order confirmation persistence will retry eventId={} checkoutId={} code={}",
                    event.eventId(),
                    event.checkoutId(),
                    "ORDER_PERSISTENCE_RETRY_REQUIRED");
            return new OrderConfirmationResult(
                    OrderConfirmationResult.Outcome.RETRY_REQUIRED,
                    null,
                    "ORDER_PERSISTENCE_RETRY_REQUIRED");
        }
    }

    private Claim claim(PaymentEventEnvelope event, String payloadHash, Instant now) {
        String token = UUID.randomUUID().toString();
        ProcessedPaymentEvent existing =
                orders.lockProcessed(properties.consumerName(), event.eventId()).orElse(null);
        if (existing != null) {
            requireSamePayload(existing, payloadHash);
            if ("COMPLETED".equals(existing.state())) {
                return Claim.stop(new OrderConfirmationResult(
                        OrderConfirmationResult.Outcome.REPLAYED,
                        existing.orderId(),
                        null));
            }
            if ("REJECTED".equals(existing.state())) {
                return Claim.stop(new OrderConfirmationResult(
                        OrderConfirmationResult.Outcome.REJECTED,
                        existing.orderId(),
                        existing.safeErrorCode()));
            }
            if ("PROCESSING".equals(existing.state())
                    && existing.claimExpiresAt().isAfter(now)) {
                return Claim.stop(new OrderConfirmationResult(
                        OrderConfirmationResult.Outcome.IN_PROGRESS,
                        null,
                        "ORDER_CONFIRMATION_IN_PROGRESS"));
            }
            if (orders.reclaim(
                    properties.consumerName(),
                    event.eventId(),
                    token,
                    now.plus(properties.processingLease()),
                    now) != 1) {
                return Claim.stop(new OrderConfirmationResult(
                        OrderConfirmationResult.Outcome.IN_PROGRESS,
                        null,
                        "ORDER_CONFIRMATION_IN_PROGRESS"));
            }
        } else {
            orders.insertProcessing(
                    properties.consumerName(),
                    event,
                    payloadHash,
                    token,
                    now.plus(properties.processingLease()),
                    now);
        }

        if ("payment.failed".equals(event.eventType())) {
            return reject(event, token, "PAYMENT_FAILED_EVENT_UNSUPPORTED", now);
        }
        CheckoutPaymentBinding payment = paymentBindings.lock(event.paymentIntentId()).orElse(null);
        CheckoutAggregate checkout = checkouts.lock(event.checkoutId()).orElse(null);
        String invalidCode = mismatchCode(event, payment, checkout);
        if (invalidCode != null) {
            return reject(event, token, invalidCode, now);
        }

        if (checkout.status() == CheckoutStatus.COMPLETED) {
            String orderId = orders.orderIdByCheckout(checkout.id()).orElse(null);
            if (orderId == null) {
                return reject(event, token, "ORDER_STATE_INCONSISTENT", now);
            }
            orders.markCompleted(
                    properties.consumerName(), event.eventId(), token, orderId, now);
            return Claim.stop(new OrderConfirmationResult(
                    OrderConfirmationResult.Outcome.REPLAYED,
                    orderId,
                    null));
        }
        if (checkout.status() == CheckoutStatus.CANCELLED
                || checkout.status() == CheckoutStatus.EXPIRED
                || checkout.status() == CheckoutStatus.FAILED
                || checkout.status() == CheckoutStatus.REFUND_REQUIRED
                || checkout.status() == CheckoutStatus.PAYMENT_REVIEW
                || !event.occurredAt().isBefore(checkout.expiresAt())) {
            return reject(event, token, RECOVERY_REQUIRED, now);
        }
        if (checkout.status() == CheckoutStatus.PENDING_PAYMENT) {
            if (checkouts.markPaymentProcessing(checkout.id(), now) != 1) {
                return reject(event, token, "CHECKOUT_STATE_CONFLICT", now);
            }
            checkouts.history(
                    ids.next(),
                    checkout.id(),
                    CheckoutStatus.PENDING_PAYMENT.name(),
                    CheckoutStatus.PAYMENT_PROCESSING.name(),
                    "PAYMENT_SUCCEEDED",
                    event.eventId(),
                    event.correlationId(),
                    event.eventId(),
                    now);
            checkout = checkouts.lock(checkout.id()).orElseThrow();
        } else if (checkout.status() != CheckoutStatus.PAYMENT_PROCESSING) {
            return reject(event, token, "CHECKOUT_STATE_ILLEGAL", now);
        }
        return Claim.continueWith(token, checkout);
    }

    private OrderConfirmationResult confirm(
            PaymentEventEnvelope event,
            String payloadHash,
            String claimToken,
            InventoryReservationClient.Reservation reservation,
            Instant now) {
        ProcessedPaymentEvent processed = orders
                .lockProcessed(properties.consumerName(), event.eventId())
                .orElseThrow(() -> invariant("ORDER_EVENT_CLAIM_LOST"));
        requireSamePayload(processed, payloadHash);
        if ("COMPLETED".equals(processed.state())) {
            return new OrderConfirmationResult(
                    OrderConfirmationResult.Outcome.REPLAYED,
                    processed.orderId(),
                    null);
        }
        if (!"PROCESSING".equals(processed.state())
                || !claimToken.equals(processed.claimToken())) {
            throw invariant("ORDER_EVENT_CLAIM_LOST");
        }

        CheckoutPaymentBinding payment = paymentBindings.lock(event.paymentIntentId())
                .orElseThrow(() -> invariant("PAYMENT_BINDING_MISSING"));
        CheckoutAggregate checkout = checkouts.lock(event.checkoutId())
                .orElseThrow(() -> invariant("CHECKOUT_MISSING"));
        String mismatch = mismatchCode(event, payment, checkout);
        if (mismatch != null || checkout.status() != CheckoutStatus.PAYMENT_PROCESSING) {
            throw invariant(mismatch == null ? "CHECKOUT_STATE_CONFLICT" : mismatch);
        }
        validateCommittedReservation(checkout, reservation);

        String existingOrder = orders.orderIdByCheckout(checkout.id()).orElse(null);
        if (existingOrder != null) {
            orders.markCompleted(
                    properties.consumerName(), event.eventId(), claimToken, existingOrder, now);
            return new OrderConfirmationResult(
                    OrderConfirmationResult.Outcome.REPLAYED,
                    existingOrder,
                    null);
        }

        Map<String, Group> groups = groups(checkout);
        String orderId = ids.next();
        orders.insertOrder(orderId, checkout, payment, now);
        Map<String, String> groupIds = new LinkedHashMap<>();
        for (Group group : groups.values()) {
            String groupId = ids.next();
            groupIds.put(group.businessId(), groupId);
            orders.insertBusinessOrder(
                    groupId,
                    orderId,
                    group.businessId(),
                    group.storeId(),
                    group.totals(),
                    now);
        }
        for (CheckoutAggregate.Item item : checkout.items()) {
            orders.insertOrderItem(
                    ids.next(),
                    orderId,
                    groupIds.get(item.businessId()),
                    item,
                    now);
        }
        orders.insertAddress(orderId, checkout.address(), now);
        orders.insertHistory(ids.next(), orderId, event.correlationId(), event.eventId(), now);
        orders.insertOutbox(
                ids.next(),
                orderId,
                orderConfirmedPayload(orderId, checkout, payment, groups.keySet().stream().toList(), now),
                event.correlationId(),
                event.eventId(),
                now);
        if (checkouts.markCompleted(
                checkout.id(), reservation.status(), reservation.version(), now) != 1) {
            throw invariant("CHECKOUT_STATE_CONFLICT");
        }
        checkouts.history(
                ids.next(),
                checkout.id(),
                CheckoutStatus.PAYMENT_PROCESSING.name(),
                CheckoutStatus.COMPLETED.name(),
                "ORDER_CONFIRMED",
                ids.next(),
                event.correlationId(),
                event.eventId(),
                now);
        orders.markCompleted(
                properties.consumerName(), event.eventId(), claimToken, orderId, now);
        log.info(
                "Confirmed paid order eventId={} checkoutId={} orderId={} businessCount={}",
                event.eventId(),
                checkout.id(),
                orderId,
                groups.size());
        return new OrderConfirmationResult(
                OrderConfirmationResult.Outcome.CONFIRMED,
                orderId,
                null);
    }

    private Claim reject(
            PaymentEventEnvelope event,
            String claimToken,
            String safeCode,
            Instant now) {
        orders.markRejected(
                properties.consumerName(), event.eventId(), claimToken, safeCode, now);
        log.warn(
                "Rejected payment event eventId={} checkoutId={} code={}",
                event.eventId(),
                event.checkoutId(),
                safeCode);
        return Claim.stop(new OrderConfirmationResult(
                OrderConfirmationResult.Outcome.REJECTED,
                null,
                safeCode));
    }

    private OrderConfirmationResult recoveryRequired(
            PaymentEventEnvelope event,
            String claimToken,
            Instant now) {
        transactions.executeWithoutResult(status -> {
            CheckoutAggregate checkout = checkouts.lock(event.checkoutId()).orElse(null);
            if (checkout != null && checkout.status() == CheckoutStatus.PAYMENT_PROCESSING) {
                checkouts.markPaymentReview(checkout.id(), now);
                checkouts.history(
                        ids.next(),
                        checkout.id(),
                        CheckoutStatus.PAYMENT_PROCESSING.name(),
                        CheckoutStatus.PAYMENT_REVIEW.name(),
                        "INVENTORY_RECOVERY_REQUIRED",
                        ids.next(),
                        event.correlationId(),
                        event.eventId(),
                        now);
            }
            orders.markRejected(
                    properties.consumerName(),
                    event.eventId(),
                    claimToken,
                    RECOVERY_REQUIRED,
                    now);
        });
        return new OrderConfirmationResult(
                OrderConfirmationResult.Outcome.REJECTED,
                null,
                RECOVERY_REQUIRED);
    }

    private void retry(
            PaymentEventEnvelope event,
            String claimToken,
            String safeCode,
            Instant now) {
        transactions.executeWithoutResult(status -> orders.markRetryable(
                properties.consumerName(), event.eventId(), claimToken, safeCode, now));
        log.warn(
                "Payment order confirmation will retry eventId={} checkoutId={} code={}",
                event.eventId(),
                event.checkoutId(),
                safeCode);
    }

    private String mismatchCode(
            PaymentEventEnvelope event,
            CheckoutPaymentBinding payment,
            CheckoutAggregate checkout) {
        if (payment == null) {
            return "PAYMENT_INTENT_UNKNOWN";
        }
        if (checkout == null) {
            return "CHECKOUT_UNKNOWN";
        }
        List<String> businesses = checkout.items().stream()
                .map(CheckoutAggregate.Item::businessId)
                .distinct()
                .sorted()
                .toList();
        boolean versionMatches = switch (checkout.status()) {
            case PENDING_PAYMENT -> payment.checkoutVersion() == checkout.version();
            case PAYMENT_PROCESSING -> payment.checkoutVersion() + 1 == checkout.version();
            case COMPLETED -> payment.checkoutVersion() + 2 == checkout.version();
            default -> payment.checkoutVersion() <= checkout.version();
        };
        boolean matches = payment.paymentIntentId().equals(event.paymentIntentId())
                && payment.checkoutId().equals(event.checkoutId())
                && payment.checkoutId().equals(checkout.id())
                && payment.buyerId().equals(checkout.buyerId())
                && payment.businessIds().equals(businesses)
                && payment.checkoutSnapshotHash().equals(checkout.cartSnapshotHash())
                && versionMatches
                && payment.amount().compareTo(checkout.total()) == 0
                && payment.amount().compareTo(event.payload().amount()) == 0
                && payment.currency().equals(checkout.currency())
                && payment.currency().equals(event.payload().currency())
                && payment.expiresAt().equals(checkout.expiresAt())
                && validSnapshots(checkout, businesses);
        return matches ? null : "PAYMENT_CHECKOUT_MISMATCH";
    }

    private boolean validSnapshots(CheckoutAggregate checkout, List<String> businesses) {
        if (checkout.items().isEmpty() || checkout.items().size() > 50 || businesses.isEmpty()) {
            return false;
        }
        Map<String, String> stores = new TreeMap<>();
        MutableTotals checkoutTotals = new MutableTotals();
        for (CheckoutAggregate.Item item : checkout.items()) {
            if (!checkout.currency().equals(item.currency())
                    || item.quantity() <= 0
                    || item.lineTotal().compareTo(
                    item.lineSubtotal()
                            .add(item.shippingAllocation())
                            .add(item.taxAllocation())
                            .subtract(item.discountAllocation())) != 0) {
                return false;
            }
            String prior = stores.putIfAbsent(item.businessId(), item.storeId());
            if (prior != null && !prior.equals(item.storeId())) {
                return false;
            }
            checkoutTotals.add(item);
        }
        List<String> policyBusinesses = checkout.policies().stream()
                .map(CheckoutAggregate.Policy::businessId)
                .distinct()
                .sorted()
                .toList();
        List<String> shippingBusinesses = checkout.shippingQuotes().stream()
                .map(CheckoutAggregate.ShippingQuote::businessId)
                .distinct()
                .sorted()
                .toList();
        boolean policyStoresMatch = checkout.policies().stream()
                .allMatch(policy -> policy.storeId().equals(stores.get(policy.businessId())));
        boolean shippingStoresMatch = checkout.shippingQuotes().stream()
                .allMatch(quote -> quote.storeId().equals(stores.get(quote.businessId()))
                        && checkout.currency().equals(quote.currency()));
        OrderConfirmationRepository.Totals totals = checkoutTotals.snapshot();
        return policyBusinesses.equals(businesses)
                && shippingBusinesses.equals(businesses)
                && policyStoresMatch
                && shippingStoresMatch
                && checkout.taxQuote() != null
                && checkout.currency().equals(checkout.taxQuote().currency())
                && totals.subtotal().compareTo(checkout.subtotal()) == 0
                && totals.shipping().compareTo(checkout.shipping()) == 0
                && totals.tax().compareTo(checkout.tax()) == 0
                && totals.discount().compareTo(checkout.discount()) == 0
                && totals.total().compareTo(checkout.total()) == 0;
    }

    private void validateCommittedReservation(
            CheckoutAggregate checkout,
            InventoryReservationClient.Reservation reservation) {
        List<InventoryReservationClient.Item> expected = checkout.items().stream()
                .map(item -> new InventoryReservationClient.Item(item.listingId(), item.quantity()))
                .sorted(java.util.Comparator.comparing(InventoryReservationClient.Item::listingId))
                .toList();
        List<InventoryReservationClient.Item> actual = reservation == null || reservation.items() == null
                ? List.of()
                : reservation.items().stream()
                        .sorted(java.util.Comparator.comparing(InventoryReservationClient.Item::listingId))
                        .toList();
        boolean valid = reservation != null
                && checkout.reservationId().equals(reservation.id())
                && checkout.id().equals(reservation.checkoutId())
                && "CHECKOUT".equals(reservation.purpose())
                && "COMMITTED".equals(reservation.status())
                && expected.equals(actual);
        if (!valid) {
            throw new OrderConfirmationException(
                    "ORDER_INVENTORY_COMMIT_MISMATCH",
                    "Committed inventory does not match the checkout.");
        }
    }

    private Map<String, Group> groups(CheckoutAggregate checkout) {
        Map<String, MutableTotals> totals = new TreeMap<>();
        Map<String, String> stores = new TreeMap<>();
        for (CheckoutAggregate.Item item : checkout.items()) {
            stores.put(item.businessId(), item.storeId());
            totals.computeIfAbsent(item.businessId(), ignored -> new MutableTotals())
                    .add(item);
        }
        Map<String, Group> result = new LinkedHashMap<>();
        totals.forEach((businessId, value) -> result.put(
                businessId,
                new Group(businessId, stores.get(businessId), value.snapshot())));
        return result;
    }

    private String orderConfirmedPayload(
            String orderId,
            CheckoutAggregate checkout,
            CheckoutPaymentBinding payment,
            List<String> businessIds,
            Instant now) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "orderId", orderId,
                    "checkoutId", checkout.id(),
                    "paymentIntentId", payment.paymentIntentId(),
                    "status", "CONFIRMED",
                    "businessIds", businessIds,
                    "confirmedAt", now.toString()));
        } catch (Exception exception) {
            throw invariant("ORDER_EVENT_SERIALIZATION_FAILED");
        }
    }

    private String payloadHash(PaymentEventEnvelope event) {
        String canonical = String.join("|",
                event.eventId(),
                event.eventType(),
                Integer.toString(event.schemaVersion()),
                event.occurredAt().toString(),
                event.correlationId(),
                event.paymentIntentId(),
                event.checkoutId(),
                event.partitionKey(),
                event.payload().status(),
                event.payload().amount().setScale(4).toPlainString(),
                event.payload().currency(),
                event.payload().providerEventId());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw invariant("ORDER_EVENT_HASH_FAILED");
        }
    }

    private void requireSamePayload(ProcessedPaymentEvent existing, String payloadHash) {
        if (!existing.payloadHash().equals(payloadHash)) {
            throw new OrderConfirmationException(
                    "PAYMENT_EVENT_PAYLOAD_CONFLICT",
                    "Payment event ID was reused with a different payload.");
        }
    }

    private OrderConfirmationException invariant(String code) {
        return new OrderConfirmationException(code, "Order confirmation could not be completed.");
    }

    private record Claim(
            boolean continueProcessing,
            String claimToken,
            CheckoutAggregate checkout,
            OrderConfirmationResult result
    ) {
        static Claim continueWith(String token, CheckoutAggregate checkout) {
            return new Claim(true, token, checkout, null);
        }

        static Claim stop(OrderConfirmationResult result) {
            return new Claim(false, null, null, result);
        }
    }

    private record Group(
            String businessId,
            String storeId,
            OrderConfirmationRepository.Totals totals
    ) {
    }

    private static final class MutableTotals {
        private BigDecimal subtotal = BigDecimal.ZERO;
        private BigDecimal shipping = BigDecimal.ZERO;
        private BigDecimal tax = BigDecimal.ZERO;
        private BigDecimal discount = BigDecimal.ZERO;
        private BigDecimal total = BigDecimal.ZERO;

        private void add(CheckoutAggregate.Item item) {
            subtotal = subtotal.add(item.lineSubtotal());
            shipping = shipping.add(item.shippingAllocation());
            tax = tax.add(item.taxAllocation());
            discount = discount.add(item.discountAllocation());
            total = total.add(item.lineTotal());
        }

        private OrderConfirmationRepository.Totals snapshot() {
            return new OrderConfirmationRepository.Totals(
                    subtotal, shipping, tax, discount, total);
        }
    }
}
