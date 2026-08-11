package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.model.CartAssessment;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutCalculationServiceTests {

    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");

    @Test
    void localDemoBuildsExactImmutableTotalsAndDeadline() {
        CheckoutProperties properties = properties(new MockEnvironment());
        CheckoutUlidGenerator ids = new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC));
        CheckoutCalculationService service = new CheckoutCalculationService(
                properties,
                () -> new PlatformPolicyProvider.Policy(
                        "01P00000000000000000000001",
                        "PLATFORM_DEFAULT",
                        "LOCAL_DEMO_V1",
                        "Shipping text",
                        "Cancellation text",
                        "Return text"),
                new LocalDemoShippingCalculationAdapter(properties),
                new LocalDemoTaxCalculationAdapter(properties),
                ids,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var checkout = service.calculate(
                "01C00000000000000000000001",
                "01U00000000000000000000001",
                assessment(),
                address());

        assertThat(checkout.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(checkout.subtotal()).isEqualByComparingTo("25.0000");
        assertThat(checkout.shipping()).isEqualByComparingTo("0.0000");
        assertThat(checkout.tax()).isEqualByComparingTo("0.0000");
        assertThat(checkout.discount()).isEqualByComparingTo("0.0000");
        assertThat(checkout.total()).isEqualByComparingTo("25.0000");
        assertThat(checkout.items()).singleElement().satisfies(item -> {
            assertThat(item.sku()).isEqualTo("SKU-1");
            assertThat(item.condition()).isEqualTo("NEW");
            assertThat(item.catalogVersion()).isEqualTo(7);
            assertThat(item.storeName()).isEqualTo("Demo Store");
            assertThat(item.policyVersion()).isEqualTo("LOCAL_DEMO_V1");
        });
        assertThat(checkout.shippingQuotes()).singleElement()
                .satisfies(quote -> assertThat(quote.adapter()).isEqualTo("FREE_LOCAL_DEMO_V1"));
        assertThat(checkout.taxQuote().adapter()).isEqualTo("ZERO_LOCAL_DEMO_V1");
    }

    @Test
    void localDemoBuildsIndependentSnapshotsForSameCurrencyMultiBusinessCart() {
        CheckoutProperties properties = properties(new MockEnvironment());
        CheckoutUlidGenerator ids = new CheckoutUlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC));
        CheckoutCalculationService service = new CheckoutCalculationService(
                properties,
                () -> new PlatformPolicyProvider.Policy(
                        "01P00000000000000000000001",
                        "PLATFORM_DEFAULT",
                        "LOCAL_DEMO_V1",
                        "Shipping text",
                        "Cancellation text",
                        "Return text"),
                new LocalDemoShippingCalculationAdapter(properties),
                new LocalDemoTaxCalculationAdapter(properties),
                ids,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var checkout = service.calculate(
                "01C00000000000000000000001",
                "01U00000000000000000000001",
                multiBusinessAssessment(),
                address());

        assertThat(checkout.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(checkout.currency()).isEqualTo("USD");
        assertThat(checkout.subtotal()).isEqualByComparingTo("30.0000");
        assertThat(checkout.shipping()).isEqualByComparingTo("0.0000");
        assertThat(checkout.tax()).isEqualByComparingTo("0.0000");
        assertThat(checkout.total()).isEqualByComparingTo("30.0000");
        assertThat(checkout.items()).hasSize(2)
                .extracting(CheckoutAggregate.Item::businessId)
                .containsExactly(
                        "01B00000000000000000000001",
                        "01B00000000000000000000002");
        assertThat(checkout.shippingQuotes()).hasSize(2)
                .allSatisfy(quote -> {
                    assertThat(quote.adapter()).isEqualTo("FREE_LOCAL_DEMO_V1");
                    assertThat(quote.amount()).isEqualByComparingTo("0.0000");
                });
        assertThat(checkout.policies()).hasSize(2)
                .allSatisfy(policy -> assertThat(policy.version()).isEqualTo("LOCAL_DEMO_V1"));
        assertThat(checkout.taxQuote().adapter()).isEqualTo("ZERO_LOCAL_DEMO_V1");
    }

    @Test
    void productionProfileRejectsLocalDemoAdapters() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> properties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden");
    }

    private CheckoutProperties properties(MockEnvironment environment) {
        return new CheckoutProperties(
                environment,
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
                null,
                "Demo Store",
                "demo-store",
                true,
                "Irvine",
                "CA");
        CartDocument cart = new CartDocument(3, NOW.plusSeconds(3600), List.of(stored));
        return new CartAssessment(
                cart,
                NOW,
                true,
                List.of(),
                List.of(new CartAssessment.Line(stored, product, 5, List.of())));
    }

    private CartAssessment multiBusinessAssessment() {
        CartStoredItem first = new CartStoredItem(
                "01L00000000000000000000001",
                2,
                new BigDecimal("12.5000"),
                "USD",
                NOW.minusSeconds(60));
        CartStoredItem second = new CartStoredItem(
                "01L00000000000000000000002",
                1,
                new BigDecimal("5.0000"),
                "USD",
                NOW.minusSeconds(30));
        ProductCommerceClient.ProductContext firstProduct = new ProductCommerceClient.ProductContext(
                first.listingId(),
                "01B00000000000000000000001",
                "01S00000000000000000000001",
                "BUSINESS",
                "First store item",
                "SKU-1",
                "NEW",
                7,
                new BigDecimal("12.5000"),
                "USD",
                "ACTIVE",
                null,
                "First Demo Store",
                "first-demo-store",
                true,
                "Irvine",
                "CA");
        ProductCommerceClient.ProductContext secondProduct = new ProductCommerceClient.ProductContext(
                second.listingId(),
                "01B00000000000000000000002",
                "01S00000000000000000000002",
                "BUSINESS",
                "Second store item",
                "SKU-2",
                "GOOD",
                4,
                new BigDecimal("5.0000"),
                "USD",
                "ACTIVE",
                null,
                "Second Demo Store",
                "second-demo-store",
                true,
                "Anaheim",
                "CA");
        CartDocument cart = new CartDocument(4, NOW.plusSeconds(3600), List.of(first, second));
        return new CartAssessment(
                cart,
                NOW,
                true,
                List.of(),
                List.of(
                        new CartAssessment.Line(first, firstProduct, 5, List.of()),
                        new CartAssessment.Line(second, secondProduct, 3, List.of())));
    }

    private BuyerIdentityClient.BuyerAddress address() {
        return new BuyerIdentityClient.BuyerAddress(
                "01A00000000000000000000001",
                "01U00000000000000000000001",
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
}
