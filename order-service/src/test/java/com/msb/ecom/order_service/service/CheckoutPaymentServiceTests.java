package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.CheckoutPaymentProperties;
import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutException;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import com.msb.ecom.order_service.repository.CheckoutRepository;
import com.msb.ecom.order_service.repository.CheckoutPaymentBindingRepository;
import com.msb.ecom.order_service.repository.OrderConfirmationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutPaymentServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");
    private static final String SUBJECT = "buyer-subject";
    private static final String BUYER_ID = "01U00000000000000000000001";
    private static final String CHECKOUT_ID = "01C00000000000000000000001";
    private static final String KEY = "payment-key-001";

    private final BuyerIdentityClient buyers = mock(BuyerIdentityClient.class);
    private final CheckoutRepository repository = mock(CheckoutRepository.class);
    private final CheckoutPaymentBindingRepository paymentBindings =
            mock(CheckoutPaymentBindingRepository.class);
    private final PaymentIntentClient client = mock(PaymentIntentClient.class);
    private CheckoutPaymentService service;

    @BeforeEach
    void setUp() {
        service = service(true, true);
        when(buyers.resolveBuyer(SUBJECT)).thenReturn(BUYER_ID);
    }

    @Test
    void mapsOnlyTheOwnedAuthoritativeMultiBusinessSnapshotAndForwardsTheSameRetryKey() {
        CheckoutAggregate checkout = checkout(CheckoutStatus.PENDING_PAYMENT, NOW.plusSeconds(900));
        when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.of(checkout));
        when(client.create(eq(KEY), any(), any())).thenAnswer(invocation ->
                intent(invocation.getArgument(2)));

        var first = service.create(CHECKOUT_ID, KEY, "correlation-payment");
        var replay = service.create(CHECKOUT_ID, KEY, "correlation-payment");

        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.amount()).isEqualByComparingTo("30.0000");
        assertThat(first.currency()).isEqualTo("USD");
        assertThat(first.action().type()).isEqualTo("FAKE_HOSTED_ACTION");

        ArgumentCaptor<PaymentIntentClient.Command> command =
                ArgumentCaptor.forClass(PaymentIntentClient.Command.class);
        verify(client, times(2)).create(eq(KEY), eq("correlation-payment"), command.capture());
        verify(paymentBindings, times(2)).insertOrVerify(any());
        assertThat(command.getAllValues()).allSatisfy(value -> {
            assertThat(value.checkoutId()).isEqualTo(CHECKOUT_ID);
            assertThat(value.checkoutVersion()).isEqualTo(4);
            assertThat(value.checkoutSnapshotHash()).isEqualTo("a".repeat(64));
            assertThat(value.buyerId()).isEqualTo(BUYER_ID);
            assertThat(value.businessIds()).containsExactly(
                    "01B00000000000000000000001",
                    "01B00000000000000000000002");
            assertThat(value.amount()).isEqualByComparingTo("30.0000");
            assertThat(value.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        });
    }

    @Test
    void defaultOffCheckoutOrPaymentGateFailsBeforeIdentityOrNetworkAccess() {
        for (CheckoutPaymentService disabled : List.of(service(false, true), service(true, false))) {
            assertThatThrownBy(() -> disabled.create(CHECKOUT_ID, KEY, "correlation"))
                    .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                        assertThat(exception.status().value()).isEqualTo(404);
                        assertThat(exception.code()).isEqualTo("CHECKOUT_PAYMENT_NOT_AVAILABLE");
                    });
        }

        verify(buyers, never()).resolveBuyer(any());
        verify(repository, never()).findOwned(any(), any());
        verify(client, never()).create(any(), any(), any());
    }

    @Test
    void crossBuyerLookupUsesPrivacyPreservingNotFoundAndNeverCallsPayment() {
        when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(CHECKOUT_ID, KEY, "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("CHECKOUT_NOT_FOUND");
                });

        verify(client, never()).create(any(), any(), any());
    }

    @Test
    void cancelledReservingAndExpiredCheckoutsAreNotPayable() {
        for (CheckoutAggregate checkout : List.of(
                checkout(CheckoutStatus.CANCELLED, NOW.plusSeconds(900)),
                checkout(CheckoutStatus.RESERVING, NOW.plusSeconds(900)),
                checkout(CheckoutStatus.PENDING_PAYMENT, NOW))) {
            when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.of(checkout));

            assertThatThrownBy(() -> service.create(CHECKOUT_ID, KEY, "correlation"))
                    .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                        assertThat(exception.status().value()).isEqualTo(409);
                        assertThat(exception.code()).isEqualTo("CHECKOUT_NOT_PAYABLE");
                    });
        }

        verify(client, never()).create(any(), any(), any());
    }

    @Test
    void malformedPaymentScopeResponseFailsClosed() {
        CheckoutAggregate checkout = checkout(CheckoutStatus.PENDING_PAYMENT, NOW.plusSeconds(900));
        when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.of(checkout));
        when(client.create(eq(KEY), any(), any())).thenReturn(new PaymentIntentClient.PaymentIntent(
                "01P00000000000000000000001",
                CHECKOUT_ID,
                checkout.version(),
                BUYER_ID,
                List.of("01B00000000000000000000999"),
                checkout.total(),
                checkout.currency(),
                "FAKE_LOCAL_DEMO_V1",
                "internal-reference",
                "REQUIRES_ACTION",
                1,
                checkout.expiresAt(),
                null,
                null));

        assertThatThrownBy(() -> service.create(CHECKOUT_ID, KEY, "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("CHECKOUT_PAYMENT_UNAVAILABLE");
                });
    }

    @Test
    void unboundedPaymentResponseMetadataFailsClosed() {
        CheckoutAggregate checkout = checkout(CheckoutStatus.PENDING_PAYMENT, NOW.plusSeconds(900));
        when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.of(checkout));
        when(client.create(eq(KEY), any(), any())).thenAnswer(invocation -> {
            PaymentIntentClient.PaymentIntent valid = intent(invocation.getArgument(2));
            return new PaymentIntentClient.PaymentIntent(
                    valid.id(),
                    valid.checkoutId(),
                    valid.checkoutVersion(),
                    valid.buyerId(),
                    valid.businessIds(),
                    valid.amount(),
                    valid.currency(),
                    "x".repeat(65),
                    valid.providerReference(),
                    valid.status(),
                    valid.version(),
                    valid.expiresAt(),
                    valid.providerAction(),
                    valid.error());
        });

        assertThatThrownBy(() -> service.create(CHECKOUT_ID, KEY, "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("CHECKOUT_PAYMENT_UNAVAILABLE");
                });
        verify(paymentBindings, never()).insertOrVerify(any());
    }

    @Test
    void invalidIdempotencyKeyIsRejectedBeforeCheckoutRead() {
        assertThatThrownBy(() -> service.create(CHECKOUT_ID, "short", "correlation"))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(400);
                    assertThat(exception.code()).isEqualTo("PAYMENT_IDEMPOTENCY_KEY_REQUIRED");
                });

        verify(repository, never()).findOwned(any(), any());
        verify(client, never()).create(any(), any(), any());
    }

    @Test
    void unsafeCorrelationIsReplacedBeforeTheInternalCall() {
        CheckoutAggregate checkout = checkout(CheckoutStatus.PENDING_PAYMENT, NOW.plusSeconds(900));
        when(repository.findOwned(CHECKOUT_ID, BUYER_ID)).thenReturn(Optional.of(checkout));
        when(client.create(eq(KEY), any(), any())).thenAnswer(invocation ->
                intent(invocation.getArgument(2)));

        service.create(CHECKOUT_ID, KEY, "x".repeat(129));

        ArgumentCaptor<String> correlation = ArgumentCaptor.forClass(String.class);
        verify(client).create(eq(KEY), correlation.capture(), any());
        assertThat(correlation.getValue())
                .hasSizeLessThanOrEqualTo(128)
                .matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                .isNotEqualTo("x".repeat(129));
    }

    @Test
    void paymentConfigurationAllowsDefaultOffButRequiresCredentialsWhenEnabled() {
        assertThatCode(() -> new CheckoutPaymentProperties(
                false,
                "http://payment.test",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CheckoutPaymentProperties(
                true,
                "http://payment.test",
                "",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private CheckoutPaymentService service(boolean checkoutEnabled, boolean paymentEnabled) {
        CurrentActorProvider actors = () ->
                new CurrentActor(SUBJECT, "token", "buyer@example.test", "Buyer", true);
        CheckoutProperties checkoutProperties = new CheckoutProperties(
                new MockEnvironment(),
                checkoutEnabled,
                "LOCAL_DEMO",
                "ZERO_LOCAL_DEMO_V1",
                "FREE_LOCAL_DEMO_V1",
                "LOCAL_DEMO_V1",
                Duration.ofMinutes(15),
                true,
                50,
                50,
                Duration.ofDays(7));
        CheckoutPaymentProperties paymentProperties = new CheckoutPaymentProperties(
                paymentEnabled,
                "http://payment.test",
                "payment-test-token",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2));
        return new CheckoutPaymentService(
                actors,
                buyers,
                repository,
                paymentBindings,
                checkoutProperties,
                paymentProperties,
                client,
                mock(OrderConfirmationRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PaymentIntentClient.PaymentIntent intent(PaymentIntentClient.Command command) {
        return new PaymentIntentClient.PaymentIntent(
                "01P00000000000000000000001",
                command.checkoutId(),
                command.checkoutVersion(),
                command.buyerId(),
                command.businessIds(),
                command.amount(),
                command.currency(),
                "FAKE_LOCAL_DEMO_V1",
                "internal-provider-reference",
                "REQUIRES_ACTION",
                1,
                command.expiresAt(),
                new PaymentIntentClient.ProviderAction(
                        "FAKE_HOSTED_ACTION",
                        "fake-action-reference"),
                null);
    }

    private CheckoutAggregate checkout(CheckoutStatus status, Instant expiresAt) {
        return new CheckoutAggregate(
                CHECKOUT_ID,
                BUYER_ID,
                status,
                4,
                3,
                "a".repeat(64),
                "USD",
                new BigDecimal("30.0000"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("30.0000"),
                expiresAt,
                "01R00000000000000000000001",
                "ACTIVE",
                1L,
                CheckoutReleaseStatus.NOT_REQUIRED,
                null,
                NOW.minusSeconds(60),
                NOW,
                new CheckoutAggregate.Address(
                        "01A00000000000000000000001",
                        1,
                        "Home",
                        "Buyer",
                        "+15550123456",
                        "1 Main St",
                        null,
                        "Irvine",
                        "CA",
                        "92618",
                        "US"),
                List.of(
                        item(1, "01B00000000000000000000002", "20.0000"),
                        item(2, "01B00000000000000000000001", "10.0000")),
                List.of(),
                new CheckoutAggregate.TaxQuote(
                        "01T00000000000000000000001",
                        BigDecimal.ZERO,
                        "USD",
                        "ZERO_LOCAL_DEMO_V1",
                        NOW),
                List.of());
    }

    private CheckoutAggregate.Item item(int line, String businessId, String total) {
        return new CheckoutAggregate.Item(
                "01I0000000000000000000000" + line,
                line,
                "01L0000000000000000000000" + line,
                businessId,
                "01S0000000000000000000000" + line,
                7,
                "Store item " + line,
                "SKU-" + line,
                "NEW",
                null,
                1,
                new BigDecimal(total),
                "USD",
                new BigDecimal(total),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(total),
                "01P0000000000000000000000" + line,
                "LOCAL_DEMO_V1");
    }
}
