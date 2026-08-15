package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.dto.CheckoutResponse;
import com.msb.ecom.order_service.dto.CreateCheckoutRequest;
import com.msb.ecom.order_service.model.CartAssessment;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.repository.CartRepository;
import com.msb.ecom.order_service.repository.CheckoutIdempotencyRecord;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final String SUBJECT = "buyer-subject";
    private static final String BUYER_ID = "01U00000000000000000000001";
    private static final String ADDRESS_ID = "01A00000000000000000000001";
    private static final String CHECKOUT_ID = "01C00000000000000000000001";
    private static final String RESERVATION_ID = "01R00000000000000000000001";

    private final CartRepository carts = mock(CartRepository.class);
    private final CartAssessmentService assessments = mock(CartAssessmentService.class);
    private final BuyerIdentityClient buyers = mock(BuyerIdentityClient.class);
    private final CheckoutCalculationService calculations = mock(CheckoutCalculationService.class);
    private final ProductCommerceClient products = mock(ProductCommerceClient.class);
    private final InventoryReservationClient inventory = mock(InventoryReservationClient.class);
    private final CheckoutRepository repository = mock(CheckoutRepository.class);
    private final CheckoutUlidGenerator ids =
            new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private CheckoutService service;

    @BeforeEach
    void setUp() {
        CurrentActorProvider actors = () ->
                new CurrentActor(SUBJECT, "token", "buyer@example.test", "Buyer", true);
        CheckoutProperties properties = new CheckoutProperties(
                new MockEnvironment(),
                true,
                "LOCAL_DEMO",
                "ZERO_LOCAL_DEMO_V1",
                "FREE_LOCAL_DEMO_V1",
                "LOCAL_DEMO_V1",
                Duration.ofMinutes(15),
                true,
                50,
                50,
                Duration.ofDays(7));
        service = new CheckoutService(
                actors,
                carts,
                assessments,
                buyers,
                calculations,
                products,
                inventory,
                repository,
                properties,
                ids,
                new CheckoutMetrics(new SimpleMeterRegistry()),
                objectMapper,
                new NoOpTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(buyers.resolveBuyer(SUBJECT)).thenReturn(BUYER_ID);
    }

    @Test
    void completedCreateReplayDoesNotReadMutableCartOrAddress() throws Exception {
        CheckoutAggregate pending = checkout(CheckoutStatus.PENDING_PAYMENT);
        CheckoutResponse stored = response(pending);
        String storedJson = objectMapper.writeValueAsString(stored);
        when(repository.idempotency(any(), eq("create-key"))).thenReturn(Optional.of(
                new CheckoutIdempotencyRecord(
                        "scope",
                        "create-key",
                        hashForCreate(),
                        "CREATE_CHECKOUT",
                        "COMPLETED",
                        CHECKOUT_ID,
                        201,
                        storedJson)));

        CheckoutResponse replay = service.create(
                "create-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation");

        assertThat(replay.id()).isEqualTo(CHECKOUT_ID);
        assertThat(replay.status()).isEqualTo("PENDING_PAYMENT");
        verify(carts, never()).get(any());
        verify(buyers, never()).resolveAddress(any(), any());
        verify(buyers, never()).requireCapability(any(), any());
    }

    @Test
    void buyingRestrictionFailsBeforeCartPersistenceOrInventoryReservation() {
        doThrow(new CheckoutException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "USER_CAPABILITY_RESTRICTED",
                "Buying is currently unavailable for this marketplace account."))
                .when(buyers).requireCapability(BUYER_ID, "USER_BUYING");

        assertThatThrownBy(() -> service.create(
                "create-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("USER_CAPABILITY_RESTRICTED");
                });

        verify(carts, never()).get(any());
        verify(repository, never()).insert(any());
        verify(inventory, never()).reserve(any(), any(), any());
    }

    @Test
    void businessNewSalesRestrictionFailsBeforeCheckoutPersistenceAndInventoryReservation() {
        CartAssessment assessment = assessment();
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        when(carts.get(SUBJECT)).thenReturn(assessment.cart());
        when(assessments.assess(assessment.cart())).thenReturn(assessment);
        when(buyers.resolveAddress(SUBJECT, ADDRESS_ID)).thenReturn(address());
        when(repository.idempotency(any(), eq("create-key"))).thenReturn(Optional.empty());
        when(repository.findActiveByBuyer(BUYER_ID)).thenReturn(Optional.empty());
        when(calculations.calculate(any(), eq(BUYER_ID), eq(assessment), any())).thenReturn(reserving);
        doThrow(new CheckoutException(org.springframework.http.HttpStatus.FORBIDDEN,
                "BUSINESS_CAPABILITY_RESTRICTED", "This business is not accepting new sales."))
                .when(buyers).requireBusinessCapabilities(
                        Set.of("01B00000000000000000000001"), "BUSINESS_NEW_SALES");

        assertThatThrownBy(() -> service.create("create-key", new CreateCheckoutRequest(3L, ADDRESS_ID), "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("BUSINESS_CAPABILITY_RESTRICTED");
                });
        verify(repository, never()).insert(any());
        verify(inventory, never()).reserve(any(), any(), any());
    }

    @Test
    void listingRestrictionFailsAtTheReservationBoundary() {
        CartAssessment assessment = assessment();
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        when(carts.get(SUBJECT)).thenReturn(assessment.cart());
        when(assessments.assess(assessment.cart())).thenReturn(assessment);
        when(buyers.resolveAddress(SUBJECT, ADDRESS_ID)).thenReturn(address());
        when(repository.idempotency(any(), eq("create-key"))).thenReturn(Optional.empty());
        when(repository.findActiveByBuyer(BUYER_ID)).thenReturn(Optional.empty());
        when(calculations.calculate(any(), eq(BUYER_ID), eq(assessment), any())).thenReturn(reserving);
        doThrow(new CheckoutException(org.springframework.http.HttpStatus.FORBIDDEN,
                "LISTING_PURCHASABILITY_RESTRICTED", "This item is unavailable for purchase."))
                .when(products).requirePurchasable(Set.of("01L00000000000000000000001"));

        assertThatThrownBy(() -> service.create("create-key", new CreateCheckoutRequest(3L, ADDRESS_ID), "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("LISTING_PURCHASABILITY_RESTRICTED");
                });
        verify(products).requirePurchasable(Set.of("01L00000000000000000000001"));
        verify(inventory, never()).reserve(any(), any(), any());
    }

    @Test
    void ambiguousInventoryFailureLeavesDurableCheckoutReservingForRecovery() {
        CartAssessment assessment = assessment();
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        when(carts.get(SUBJECT)).thenReturn(assessment.cart());
        when(assessments.assess(assessment.cart())).thenReturn(assessment);
        when(buyers.resolveAddress(SUBJECT, ADDRESS_ID)).thenReturn(address());
        when(repository.idempotency(any(), eq("create-key"))).thenReturn(Optional.empty());
        when(repository.findActiveByBuyer(BUYER_ID)).thenReturn(Optional.empty());
        when(calculations.calculate(any(), eq(BUYER_ID), eq(assessment), any())).thenReturn(reserving);
        when(inventory.reserve(eq(CHECKOUT_ID), eq(reserving.expiresAt()), any())).thenThrow(
                new CheckoutException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "CHECKOUT_RESERVATION_PENDING",
                        "Inventory reservation is still being reconciled."));

        assertThatThrownBy(() -> service.create(
                "create-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation"))
                .isInstanceOf(CheckoutException.class)
                .extracting(exception -> ((CheckoutException) exception).code())
                .isEqualTo("CHECKOUT_RESERVATION_PENDING");

        verify(repository).insert(reserving);
        verify(repository).insertCartReconciliation(
                CHECKOUT_ID,
                SUBJECT,
                assessment.cart().version(),
                assessment.cart().items(),
                NOW);
        verify(repository).insertIdempotency(
                any(), any(), eq("create-key"), any(), eq("CREATE_CHECKOUT"),
                eq(CHECKOUT_ID), eq(NOW), eq(NOW.plus(Duration.ofDays(7))));
        verify(repository, never()).markFailed(any(), any(), any());
        verify(repository, never()).completeIdempotency(any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void crossBuyerCheckoutLookupReturnsPrivacyPreservingNotFound() {
        when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(CHECKOUT_ID))
                .isInstanceOf(CheckoutException.class)
                .satisfies(exception -> {
                    CheckoutException checkoutException = (CheckoutException) exception;
                    assertThat(checkoutException.status().value()).isEqualTo(404);
                    assertThat(checkoutException.code()).isEqualTo("CHECKOUT_NOT_FOUND");
                });
    }

    @Test
    void createRejectsAddressOwnedByAnotherBuyerWithoutPersistingCheckout() {
        CartAssessment assessment = assessment();
        when(carts.get(SUBJECT)).thenReturn(assessment.cart());
        when(assessments.assess(assessment.cart())).thenReturn(assessment);
        when(buyers.resolveAddress(SUBJECT, ADDRESS_ID)).thenReturn(new BuyerIdentityClient.BuyerAddress(
                ADDRESS_ID,
                "01U00000000000000000000999",
                "Other",
                "Another Buyer",
                "+15550999999",
                "99 Other St",
                null,
                "Irvine",
                "CA",
                "92618",
                "US",
                1));

        assertThatThrownBy(() -> service.create(
                "address-owner-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation"))
                .isInstanceOf(CheckoutException.class)
                .satisfies(exception -> {
                    CheckoutException checkoutException = (CheckoutException) exception;
                    assertThat(checkoutException.status().value()).isEqualTo(404);
                    assertThat(checkoutException.code()).isEqualTo("CHECKOUT_NOT_FOUND");
                });

        verify(repository, never()).insert(any());
        verify(calculations, never()).calculate(any(), any(), any(), any());
        verify(inventory, never()).reserve(any(), any(), any());
    }

    @Test
    void createRejectsReusedIdempotencyKeyWithDifferentPayloadHash() {
        when(repository.idempotency(any(), eq("reused-create-key"))).thenReturn(Optional.of(
                new CheckoutIdempotencyRecord(
                        "scope",
                        "reused-create-key",
                        "f".repeat(64),
                        "CREATE_CHECKOUT",
                        "COMPLETED",
                        CHECKOUT_ID,
                        201,
                        "{}")));

        assertThatThrownBy(() -> service.create(
                "reused-create-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation"))
                .isInstanceOf(CheckoutException.class)
                .extracting(exception -> ((CheckoutException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        verify(carts, never()).get(any());
        verify(inventory, never()).reserve(any(), any(), any());
    }

    @Test
    void concurrentCreateDuplicateReplaysCompletedResultWithoutSecondReservation() throws Exception {
        CartAssessment assessment = assessment();
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        CheckoutResponse stored = response(checkout(CheckoutStatus.PENDING_PAYMENT));
        CheckoutIdempotencyRecord completed = new CheckoutIdempotencyRecord(
                "scope",
                "concurrent-create-key",
                hashForCreate(),
                "CREATE_CHECKOUT",
                "COMPLETED",
                CHECKOUT_ID,
                201,
                objectMapper.writeValueAsString(stored));
        when(repository.idempotency(any(), eq("concurrent-create-key")))
                .thenReturn(Optional.empty(), Optional.of(completed));
        when(carts.get(SUBJECT)).thenReturn(assessment.cart());
        when(assessments.assess(assessment.cart())).thenReturn(assessment);
        when(buyers.resolveAddress(SUBJECT, ADDRESS_ID)).thenReturn(address());
        when(repository.findActiveByBuyer(BUYER_ID)).thenReturn(Optional.empty());
        when(calculations.calculate(any(), eq(BUYER_ID), eq(assessment), any())).thenReturn(reserving);
        doThrow(new DuplicateKeyException("concurrent checkout insert")).when(repository).insert(reserving);

        CheckoutResponse replay = service.create(
                "concurrent-create-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation");

        assertThat(replay.id()).isEqualTo(CHECKOUT_ID);
        assertThat(replay.status()).isEqualTo("PENDING_PAYMENT");
        verify(inventory, never()).reserve(any(), any(), any());
    }

    @Test
    void cancelReleasesInventoryAndPersistsObservedRelease() {
        CheckoutAggregate pending = checkout(CheckoutStatus.PENDING_PAYMENT);
        CheckoutAggregate cancelled = lifecycleCheckout(
                CheckoutStatus.CANCELLED,
                CheckoutReleaseStatus.PENDING,
                "ACTIVE",
                1L,
                pending.expiresAt());
        CheckoutAggregate released = lifecycleCheckout(
                CheckoutStatus.CANCELLED,
                CheckoutReleaseStatus.COMPLETE,
                "RELEASED",
                2L,
                pending.expiresAt());
        when(repository.lockOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.of(pending));
        when(repository.find(CHECKOUT_ID))
                .thenReturn(Optional.of(cancelled), Optional.of(released));
        when(inventory.release(CHECKOUT_ID, RESERVATION_ID, "CHECKOUT_CANCELLED"))
                .thenReturn(reservation(released, "RELEASED", false, 2L));

        CheckoutResponse response = service.cancel(CHECKOUT_ID, "cancel-release-key", "correlation");

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.reservation().releaseStatus()).isEqualTo("COMPLETE");
        verify(repository).cancel(CHECKOUT_ID, NOW);
        verify(inventory).release(CHECKOUT_ID, RESERVATION_ID, "CHECKOUT_CANCELLED");
        verify(repository).releaseComplete(CHECKOUT_ID, "RELEASED", 2L, NOW);
    }

    @Test
    void recoveryRetryCompletesPreviouslyAmbiguousReservation() {
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        CheckoutAggregate pending = checkout(CheckoutStatus.PENDING_PAYMENT);
        InventoryReservationClient.Reservation active =
                reservation(pending, "ACTIVE", true, 1L);
        when(repository.reservingIds(50)).thenReturn(List.of(CHECKOUT_ID));
        when(repository.find(CHECKOUT_ID))
                .thenReturn(Optional.of(reserving), Optional.of(pending));
        when(repository.lock(CHECKOUT_ID)).thenReturn(Optional.of(reserving));
        when(repository.markPending(CHECKOUT_ID, RESERVATION_ID, "ACTIVE", 1L, NOW)).thenReturn(1);
        when(inventory.reserve(CHECKOUT_ID, reserving.expiresAt(), List.of(
                new InventoryReservationClient.Line("01L00000000000000000000001", 2))))
                .thenReturn(active);
        when(inventory.get(RESERVATION_ID)).thenReturn(active);

        assertThat(service.recoverReserving()).isEqualTo(1);

        verify(repository).markPending(CHECKOUT_ID, RESERVATION_ID, "ACTIVE", 1L, NOW);
        verify(repository).outbox(
                any(), eq(CHECKOUT_ID), eq("checkout.created.v1"), any(), any(), any(), eq(NOW));
    }

    @Test
    void repeatedCreateReconcilesAnActiveReservingCheckout() {
        CartAssessment assessment = assessment();
        CheckoutAggregate reserving = checkout(CheckoutStatus.RESERVING);
        CheckoutAggregate pending = checkout(CheckoutStatus.PENDING_PAYMENT);
        InventoryReservationClient.Reservation active = reservation(pending, "ACTIVE", true, 1L);
        when(repository.idempotency(any(), eq("retry-with-new-key"))).thenReturn(Optional.empty());
        when(carts.get(SUBJECT)).thenReturn(assessment.cart());
        when(assessments.assess(assessment.cart())).thenReturn(assessment);
        when(buyers.resolveAddress(SUBJECT, ADDRESS_ID)).thenReturn(address());
        when(repository.findActiveByBuyer(BUYER_ID)).thenReturn(Optional.of(reserving));
        when(inventory.reserve(CHECKOUT_ID, reserving.expiresAt(), List.of(
                new InventoryReservationClient.Line("01L00000000000000000000001", 2))))
                .thenReturn(active);
        when(inventory.get(RESERVATION_ID)).thenReturn(active);
        when(repository.lock(CHECKOUT_ID)).thenReturn(Optional.of(reserving));
        when(repository.markPending(CHECKOUT_ID, RESERVATION_ID, "ACTIVE", 1L, NOW)).thenReturn(1);
        when(repository.find(CHECKOUT_ID)).thenReturn(Optional.of(pending));

        CheckoutResponse response = service.create(
                "retry-with-new-key",
                new CreateCheckoutRequest(3L, ADDRESS_ID),
                "correlation");

        assertThat(response.status()).isEqualTo("PENDING_PAYMENT");
        verify(repository, never()).insert(any());
        verify(calculations, never()).calculate(any(), any(), any(), any());
    }

    @Test
    void expiryRecoveryReleasesInventoryAfterCheckoutExpires() {
        CheckoutAggregate due = lifecycleCheckout(
                CheckoutStatus.PENDING_PAYMENT,
                CheckoutReleaseStatus.NOT_REQUIRED,
                "ACTIVE",
                1L,
                NOW.minusSeconds(1));
        CheckoutAggregate expired = lifecycleCheckout(
                CheckoutStatus.EXPIRED,
                CheckoutReleaseStatus.PENDING,
                "ACTIVE",
                1L,
                NOW.minusSeconds(1));
        when(repository.lockDueExpiryIds(NOW, 50)).thenReturn(List.of(CHECKOUT_ID));
        when(repository.lock(CHECKOUT_ID)).thenReturn(Optional.of(due));
        when(repository.find(CHECKOUT_ID))
                .thenReturn(Optional.of(expired), Optional.of(expired));
        when(repository.releasePendingIds(50)).thenReturn(List.of(CHECKOUT_ID));
        when(inventory.release(CHECKOUT_ID, RESERVATION_ID, "SYSTEM_RECOVERY"))
                .thenReturn(reservation(expired, "RELEASED", false, 2L));

        assertThat(service.expireDue()).isEqualTo(1);
        assertThat(service.reconcilePendingReleases()).isEqualTo(1);

        verify(repository).expire(CHECKOUT_ID, NOW);
        verify(inventory).release(CHECKOUT_ID, RESERVATION_ID, "SYSTEM_RECOVERY");
        verify(repository).releaseComplete(CHECKOUT_ID, "RELEASED", 2L, NOW);
    }

    private CartAssessment assessment() {
        CartStoredItem stored = new CartStoredItem(
                "01L00000000000000000000001",
                2,
                new BigDecimal("12.5000"),
                "USD",
                NOW.minusSeconds(60));
        ProductCommerceClient.ProductContext product = new ProductCommerceClient.ProductContext(
                stored.listingId(),
                "01B00000000000000000000001",
                "01S00000000000000000000001",
                "BUSINESS",
                "Store item",
                "SKU-1",
                "NEW",
                7,
                new BigDecimal("12.5000"),
                "USD",
                "ACTIVE",
                null);
        CartDocument cart = new CartDocument(3, NOW.plusSeconds(3600), List.of(stored));
        return new CartAssessment(
                cart,
                NOW,
                true,
                List.of(),
                List.of(new CartAssessment.Line(stored, product, 5, List.of())));
    }

    private BuyerIdentityClient.BuyerAddress address() {
        return new BuyerIdentityClient.BuyerAddress(
                ADDRESS_ID,
                BUYER_ID,
                "Home",
                "Buyer",
                "+15550123456",
                "1 Main St",
                null,
                "Irvine",
                "CA",
                "92618",
                "US",
                2);
    }

    private CheckoutAggregate checkout(CheckoutStatus status) {
        CheckoutAggregate calculation = new CheckoutAggregate(
                CHECKOUT_ID,
                BUYER_ID,
                CheckoutStatus.RESERVING,
                0,
                3,
                "a".repeat(64),
                "USD",
                new BigDecimal("25.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("25.0000"),
                NOW.plusSeconds(900),
                null,
                null,
                null,
                CheckoutReleaseStatus.NOT_REQUIRED,
                null,
                NOW,
                NOW,
                new CheckoutAggregate.Address(
                        ADDRESS_ID, 2, "Home", "Buyer", "+15550123456",
                        "1 Main St", null, "Irvine", "CA", "92618", "US"),
                List.of(new CheckoutAggregate.Item(
                        "01I00000000000000000000001",
                        1,
                        "01L00000000000000000000001",
                        "01B00000000000000000000001",
                        "01S00000000000000000000001",
                        7,
                        "Store item",
                        "SKU-1",
                        "NEW",
                        null,
                        2,
                        new BigDecimal("12.5000"),
                        "USD",
                        new BigDecimal("25.0000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("25.0000"),
                        "01P00000000000000000000001",
                        "LOCAL_DEMO_V1")),
                List.of(new CheckoutAggregate.ShippingQuote(
                        "01S10000000000000000000001",
                        "01B00000000000000000000001",
                        "01S00000000000000000000001",
                        "FREE_LOCAL_DEMO",
                        BigDecimal.ZERO,
                        "USD",
                        "FREE_LOCAL_DEMO_V1",
                        NOW)),
                new CheckoutAggregate.TaxQuote(
                        "01T00000000000000000000001",
                        BigDecimal.ZERO,
                        "USD",
                        "ZERO_LOCAL_DEMO_V1",
                        NOW),
                List.of(new CheckoutAggregate.Policy(
                        "01P00000000000000000000001",
                        "01B00000000000000000000001",
                        "01S00000000000000000000001",
                        "01KXQCHKPOLICYLOCALDEMOV10",
                        "PLATFORM_DEFAULT",
                        "LOCAL_DEMO_V1",
                        "Shipping text",
                        "Cancellation text",
                        "Return text")));
        if (status == CheckoutStatus.RESERVING) {
            return calculation;
        }
        return new CheckoutAggregate(
                calculation.id(),
                calculation.buyerId(),
                status,
                1,
                calculation.cartVersion(),
                calculation.cartSnapshotHash(),
                calculation.currency(),
                calculation.subtotal(),
                calculation.shipping(),
                calculation.tax(),
                calculation.discount(),
                calculation.total(),
                calculation.expiresAt(),
                RESERVATION_ID,
                "ACTIVE",
                1L,
                calculation.releaseStatus(),
                null,
                calculation.createdAt(),
                calculation.updatedAt(),
                calculation.address(),
                calculation.items(),
                calculation.shippingQuotes(),
                calculation.taxQuote(),
                calculation.policies());
    }

    private CheckoutAggregate lifecycleCheckout(
            CheckoutStatus status,
            CheckoutReleaseStatus releaseStatus,
            String reservationStatus,
            Long reservationVersion,
            Instant expiresAt) {
        CheckoutAggregate source = checkout(CheckoutStatus.PENDING_PAYMENT);
        return new CheckoutAggregate(
                source.id(),
                source.buyerId(),
                status,
                source.version(),
                source.cartVersion(),
                source.cartSnapshotHash(),
                source.currency(),
                source.subtotal(),
                source.shipping(),
                source.tax(),
                source.discount(),
                source.total(),
                expiresAt,
                source.reservationId(),
                reservationStatus,
                reservationVersion,
                releaseStatus,
                source.failureCode(),
                source.createdAt(),
                NOW,
                source.address(),
                source.items(),
                source.shippingQuotes(),
                source.taxQuote(),
                source.policies());
    }

    private InventoryReservationClient.Reservation reservation(
            CheckoutAggregate checkout,
            String status,
            boolean usable,
            long version) {
        return new InventoryReservationClient.Reservation(
                RESERVATION_ID,
                CHECKOUT_ID,
                "CHECKOUT",
                status,
                usable,
                checkout.expiresAt(),
                version,
                checkout.items().stream()
                        .map(item -> new InventoryReservationClient.Item(item.listingId(), item.quantity()))
                        .toList());
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
                new CheckoutResponse.Reservation(RESERVATION_ID, "ACTIVE", "NOT_REQUIRED"),
                new CheckoutResponse.Address(
                        ADDRESS_ID, 2, "Home", "Buyer", "+15550123456",
                        "1 Main St", null, "Irvine", "CA", "92618", "US"),
                List.of(),
                List.of(),
                new CheckoutResponse.TaxQuote(BigDecimal.ZERO, "ZERO_LOCAL_DEMO_V1"),
                List.of(),
                null,
                NOW,
                NOW);
    }

    private String hashForCreate() {
        try {
            var method = CheckoutService.class.getDeclaredMethod("hash", String[].class);
            method.setAccessible(true);
            return (String) method.invoke(service, (Object) new String[] {
                    "CREATE", SUBJECT, "3", ADDRESS_ID
            });
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
