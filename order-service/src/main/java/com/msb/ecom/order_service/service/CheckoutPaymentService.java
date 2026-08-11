package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.correlation.CorrelationId;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutPaymentProperties;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.dto.CheckoutPaymentIntentResponse;
import com.msb.ecom.order_service.dto.CheckoutOrderResolutionResponse;
import com.msb.ecom.order_service.dto.DemoPaymentCompletionResponse;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.model.CheckoutPaymentBinding;
import com.msb.ecom.order_service.model.OrderConfirmationException;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import com.msb.ecom.order_service.repository.OrderConfirmationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CheckoutPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutPaymentService.class);

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final Pattern ULID = Pattern.compile("[0-7][0-9A-HJKMNP-TV-Z]{25}");
    private static final Pattern BUSINESS_ID = Pattern.compile("[0-9A-Z]{26}");
    private static final Pattern SNAPSHOT_HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> PAYMENT_STATUSES = Set.of(
            "CREATED",
            "REQUIRES_ACTION",
            "PROCESSING",
            "SUCCEEDED",
            "FAILED");

    private final CurrentActorProvider actorProvider;
    private final BuyerIdentityClient buyerIdentityClient;
    private final CheckoutRepository repository;
    private final CheckoutPaymentBindingRepository paymentBindings;
    private final CheckoutProperties checkoutProperties;
    private final CheckoutPaymentProperties paymentProperties;
    private final PaymentIntentClient paymentClient;
    private final OrderConfirmationRepository orders;
    private final Clock clock;

    @Autowired
    public CheckoutPaymentService(
            CurrentActorProvider actorProvider,
            BuyerIdentityClient buyerIdentityClient,
            CheckoutRepository repository,
            CheckoutPaymentBindingRepository paymentBindings,
            CheckoutProperties checkoutProperties,
            CheckoutPaymentProperties paymentProperties,
            PaymentIntentClient paymentClient,
            OrderConfirmationRepository orders) {
        this(
                actorProvider,
                buyerIdentityClient,
                repository,
                paymentBindings,
                checkoutProperties,
                paymentProperties,
                paymentClient,
                orders,
                Clock.systemUTC());
    }

    CheckoutPaymentService(
            CurrentActorProvider actorProvider,
            BuyerIdentityClient buyerIdentityClient,
            CheckoutRepository repository,
            CheckoutPaymentBindingRepository paymentBindings,
            CheckoutProperties checkoutProperties,
            CheckoutPaymentProperties paymentProperties,
            PaymentIntentClient paymentClient,
            OrderConfirmationRepository orders,
            Clock clock) {
        this.actorProvider = actorProvider;
        this.buyerIdentityClient = buyerIdentityClient;
        this.repository = repository;
        this.paymentBindings = paymentBindings;
        this.checkoutProperties = checkoutProperties;
        this.paymentProperties = paymentProperties;
        this.paymentClient = paymentClient;
        this.orders = orders;
        this.clock = clock;
    }

    // Completes only the deterministic fake action bound to this buyer's immutable checkout.
    public DemoPaymentCompletionResponse completeDemo(String checkoutId, String correlationId) {
        requireEnabled();
        String buyerId = buyerIdentityClient.resolveBuyer(actorProvider.currentActor().subject());
        CheckoutAggregate checkout = repository.findOwned(checkoutId, buyerId)
                .orElseThrow(this::notFound);
        requirePayable(checkout);
        CheckoutPaymentBinding binding = paymentBindings.findByCheckout(checkoutId)
                .orElseThrow(() -> new CheckoutException(
                        HttpStatus.CONFLICT,
                        "PAYMENT_INTENT_REQUIRED",
                        "Create the demo payment intent before completing payment."));
        PaymentIntentClient.DemoCompletion result = paymentClient.completeDemo(
                binding.paymentIntentId(),
                buyerId,
                "fake_action_" + binding.paymentIntentId().toLowerCase(),
                CorrelationId.acceptOrGenerate(correlationId).value());
        return new DemoPaymentCompletionResponse(
                result.paymentIntentId(), result.status(), result.outcome(), result.replayed());
    }

    // Resolves the confirmed order only through an owned checkout reference.
    public CheckoutOrderResolutionResponse confirmedOrder(String checkoutId) {
        requireEnabled();
        String buyerId = buyerIdentityClient.resolveBuyer(actorProvider.currentActor().subject());
        repository.findOwned(checkoutId, buyerId).orElseThrow(this::notFound);
        String orderId = orders.orderIdByCheckout(checkoutId).orElse(null);
        return new CheckoutOrderResolutionResponse(orderId, orderId != null);
    }

    // Creates a payment intent solely from the authenticated buyer's immutable checkout snapshot.
    public CheckoutPaymentIntentResponse create(
            String checkoutId,
            String idempotencyKey,
            String correlationId) {
        requireEnabled();
        String key = requiredKey(idempotencyKey);
        String subject = actorProvider.currentActor().subject();
        String buyerId = buyerIdentityClient.resolveBuyer(subject);
        CheckoutAggregate checkout = repository.findOwned(checkoutId, buyerId)
                .orElseThrow(this::notFound);
        requirePayable(checkout);

        List<String> businessIds = checkout.items().stream()
                .map(CheckoutAggregate.Item::businessId)
                .distinct()
                .sorted()
                .toList();
        if (businessIds.isEmpty() || businessIds.size() > 50) {
            throw invalidSnapshot();
        }
        boolean currenciesMatch = checkout.items().stream()
                .allMatch(item -> checkout.currency().equals(item.currency()));
        // Business IDs are opaque platform IDs and include approved legacy deterministic fixtures.
        boolean scopesValid = businessIds.stream().allMatch(id -> BUSINESS_ID.matcher(id).matches());
        if (!currenciesMatch
                || !scopesValid
                || !"USD".equals(checkout.currency())
                || checkout.version() < 0
                || checkout.cartSnapshotHash() == null
                || !SNAPSHOT_HASH.matcher(checkout.cartSnapshotHash()).matches()
                || checkout.total().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn(
                    "Checkout payment snapshot rejected checkoutId={} currenciesMatch={} scopesValid={} "
                            + "currencySupported={} versionValid={} snapshotHashValid={} totalPositive={}",
                    checkout.id(), currenciesMatch, scopesValid, "USD".equals(checkout.currency()),
                    checkout.version() >= 0,
                    checkout.cartSnapshotHash() != null
                            && SNAPSHOT_HASH.matcher(checkout.cartSnapshotHash()).matches(),
                    checkout.total().compareTo(BigDecimal.ZERO) > 0);
            throw invalidSnapshot();
        }

        PaymentIntentClient.Command command = new PaymentIntentClient.Command(
                checkout.id(),
                checkout.version(),
                checkout.cartSnapshotHash(),
                checkout.buyerId(),
                businessIds,
                checkout.total(),
                checkout.currency(),
                checkout.expiresAt());
        PaymentIntentClient.PaymentIntent intent = paymentClient.create(
                key,
                CorrelationId.acceptOrGenerate(correlationId).value(),
                command);
        verifyResponse(command, intent);
        try {
            paymentBindings.insertOrVerify(new CheckoutPaymentBinding(
                    intent.id(),
                    command.checkoutId(),
                    command.checkoutVersion(),
                    command.checkoutSnapshotHash(),
                    command.buyerId(),
                    command.businessIds(),
                    command.amount(),
                    command.currency(),
                    command.expiresAt(),
                    clock.instant()));
        } catch (OrderConfirmationException exception) {
            throw invalidSnapshot();
        }
        return response(intent);
    }

    private void requireEnabled() {
        if (!checkoutProperties.enabled() || !paymentProperties.enabled()) {
            throw new CheckoutException(
                    HttpStatus.NOT_FOUND,
                    "CHECKOUT_PAYMENT_NOT_AVAILABLE",
                    "Payment is not available.");
        }
    }

    private String requiredKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new CheckoutException(
                    HttpStatus.BAD_REQUEST,
                    "PAYMENT_IDEMPOTENCY_KEY_REQUIRED",
                    "A valid Idempotency-Key is required.");
        }
        return key;
    }

    private void requirePayable(CheckoutAggregate checkout) {
        if (checkout.status() != CheckoutStatus.PENDING_PAYMENT
                || !checkout.expiresAt().isAfter(clock.instant())
                || !"ACTIVE".equals(checkout.reservationStatus())
                || checkout.releaseStatus() != CheckoutReleaseStatus.NOT_REQUIRED) {
            throw new CheckoutException(
                    HttpStatus.CONFLICT,
                    "CHECKOUT_NOT_PAYABLE",
                    "This checkout cannot start payment.");
        }
    }

    private void verifyResponse(
            PaymentIntentClient.Command command,
            PaymentIntentClient.PaymentIntent intent) {
        boolean matches = intent != null
                && command.checkoutId().equals(intent.checkoutId())
                && command.checkoutVersion() == intent.checkoutVersion()
                && command.buyerId().equals(intent.buyerId())
                && command.businessIds().equals(intent.businessIds())
                && intent.amount() != null
                && command.amount().compareTo(intent.amount()) == 0
                && validAmount(intent.amount())
                && command.currency().equals(intent.currency())
                && command.expiresAt().equals(intent.expiresAt())
                && intent.id() != null
                && ULID.matcher(intent.id()).matches()
                && bounded(intent.provider(), 64)
                && (intent.providerReference() == null
                        || bounded(intent.providerReference(), 128))
                && PAYMENT_STATUSES.contains(intent.status())
                && intent.version() >= 0
                && validAction(intent)
                && validError(intent);
        if (!matches) {
            log.warn(
                    "Payment intent response rejected checkoutId={} responsePresent={} checkoutMatches={} "
                            + "versionMatches={} buyerMatches={} businessesMatch={} amountMatches={} "
                            + "currencyMatches={} expiryMatches={} idValid={} providerValid={} statusValid={} "
                            + "actionValid={} errorValid={}",
                    command.checkoutId(), intent != null,
                    intent != null && command.checkoutId().equals(intent.checkoutId()),
                    intent != null && command.checkoutVersion() == intent.checkoutVersion(),
                    intent != null && command.buyerId().equals(intent.buyerId()),
                    intent != null && command.businessIds().equals(intent.businessIds()),
                    intent != null && intent.amount() != null
                            && command.amount().compareTo(intent.amount()) == 0 && validAmount(intent.amount()),
                    intent != null && command.currency().equals(intent.currency()),
                    intent != null && command.expiresAt().equals(intent.expiresAt()),
                    intent != null && intent.id() != null && ULID.matcher(intent.id()).matches(),
                    intent != null && bounded(intent.provider(), 64),
                    intent != null && PAYMENT_STATUSES.contains(intent.status()),
                    intent != null && validAction(intent),
                    intent != null && validError(intent));
            throw new CheckoutException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "CHECKOUT_PAYMENT_UNAVAILABLE",
                    "Payment is temporarily unavailable.");
        }
    }

    private boolean validAction(PaymentIntentClient.PaymentIntent intent) {
        if (intent.providerAction() == null) {
            return !"REQUIRES_ACTION".equals(intent.status());
        }
        return "REQUIRES_ACTION".equals(intent.status())
                && bounded(intent.providerAction().type(), 64)
                && bounded(intent.providerAction().reference(), 512)
                && (intent.providerAction().publicKey() == null
                    || bounded(intent.providerAction().publicKey(), 128))
                && (intent.providerAction().returnUrl() == null
                    || bounded(intent.providerAction().returnUrl(), 512));
    }

    private boolean validError(PaymentIntentClient.PaymentIntent intent) {
        if (intent.error() == null) {
            return !"FAILED".equals(intent.status());
        }
        return "FAILED".equals(intent.status())
                && bounded(intent.error().code(), 64)
                && bounded(intent.error().message(), 240);
    }

    private boolean validAmount(BigDecimal amount) {
        return amount.scale() >= 0
                && amount.scale() <= 4
                && amount.precision() <= 19;
    }

    private boolean bounded(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength
                && value.chars().noneMatch(character -> Character.isISOControl(character));
    }

    private CheckoutPaymentIntentResponse response(PaymentIntentClient.PaymentIntent intent) {
        CheckoutPaymentIntentResponse.ProviderAction action = intent.providerAction() == null
                ? null
                : new CheckoutPaymentIntentResponse.ProviderAction(
                        intent.providerAction().type(),
                        intent.providerAction().reference(),
                        intent.providerAction().publicKey(),
                        intent.providerAction().returnUrl());
        CheckoutPaymentIntentResponse.SafeError error = intent.error() == null
                ? null
                : new CheckoutPaymentIntentResponse.SafeError(
                        intent.error().code(),
                        intent.error().message());
        return new CheckoutPaymentIntentResponse(
                intent.id(),
                intent.checkoutId(),
                intent.status(),
                intent.version(),
                intent.amount(),
                intent.currency(),
                intent.expiresAt(),
                action,
                error);
    }

    private CheckoutException invalidSnapshot() {
        return new CheckoutException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CHECKOUT_PAYMENT_UNAVAILABLE",
                "Payment is temporarily unavailable.");
    }

    private CheckoutException notFound() {
        return new CheckoutException(
                HttpStatus.NOT_FOUND,
                "CHECKOUT_NOT_FOUND",
                "Checkout was not found.");
    }
}
