package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CartValidationServiceTests {

    private static final String USER_ID = "01U00000000000000000000001";
    private static final String LISTING_ID = "01L00000000000000000000001";
    private static final String SECOND_LISTING_ID = "01L00000000000000000000002";
    private static final String BUSINESS_ID = "01B00000000000000000000001";
    private static final String STORE_ID = "01S00000000000000000000001";
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    private CurrentActorProvider actorProvider;
    private CartRepository repository;
    private ProductCommerceClient productClient;
    private InventoryAvailabilityClient inventoryClient;
    private BusinessStoreEligibilityClient eligibilityClient;
    private CartValidationService service;

    @BeforeEach
    void setUp() {
        actorProvider = mock(CurrentActorProvider.class);
        repository = mock(CartRepository.class);
        productClient = mock(ProductCommerceClient.class);
        inventoryClient = mock(InventoryAvailabilityClient.class);
        eligibilityClient = mock(BusinessStoreEligibilityClient.class);
        when(actorProvider.currentActor()).thenReturn(new CurrentActor(
                USER_ID,
                "buyer-token",
                "buyer@example.test",
                "Buyer",
                true));
        service = new CartValidationService(
                actorProvider,
                repository,
                productClient,
                inventoryClient,
                eligibilityClient,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void readyCartUsesCurrentPriceAndDoesNotMutateRedis() {
        when(repository.get(USER_ID)).thenReturn(cart(item(LISTING_ID, 2, "12.50", "USD")));
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product(LISTING_ID, "12.50", "USD", "ACTIVE")));
        when(inventoryClient.get(LISTING_ID)).thenReturn(availability(LISTING_ID, BUSINESS_ID, 4));
        when(eligibilityClient.find(BUSINESS_ID, STORE_ID)).thenReturn(Optional.of(eligibility(true)));

        var response = service.validate();

        assertThat(response.checkoutReady()).isTrue();
        assertThat(response.cartVersion()).isEqualTo(3);
        assertThat(response.validatedAt()).isEqualTo(NOW);
        assertThat(response.validatedTotals()).singleElement()
                .satisfies(total -> {
                    assertThat(total.currency()).isEqualTo("USD");
                    assertThat(total.amount()).isEqualByComparingTo("25.00");
                });
        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("READY");
                    assertThat(item.availableQuantity()).isEqualTo(4);
                    assertThat(item.issues()).isEmpty();
                });
        verify(repository).get(USER_ID);
        verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).replaceQuantity(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(repository, never()).clear(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void quantityAndPriceChangesReturnBothIssuesWithQuantityPriority() {
        when(repository.get(USER_ID)).thenReturn(cart(item(LISTING_ID, 3, "12.50", "USD")));
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product(LISTING_ID, "14.00", "USD", "ACTIVE")));
        when(inventoryClient.get(LISTING_ID)).thenReturn(availability(LISTING_ID, BUSINESS_ID, 2));
        when(eligibilityClient.find(BUSINESS_ID, STORE_ID)).thenReturn(Optional.of(eligibility(true)));

        var response = service.validate();

        assertThat(response.checkoutReady()).isFalse();
        assertThat(response.validatedTotals()).isEmpty();
        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("QUANTITY_REDUCED");
                    assertThat(item.availableQuantity()).isEqualTo(2);
                    assertThat(item.currentPrice()).isEqualByComparingTo("14.00");
                    assertThat(item.issues()).extracting("code")
                            .containsExactly("CART_QUANTITY_REDUCED", "CART_PRICE_CHANGED");
                });
    }

    @Test
    void sellerAndStockFailuresAreActionableAndFailReadiness() {
        when(repository.get(USER_ID)).thenReturn(cart(item(LISTING_ID, 1, "12.50", "USD")));
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product(LISTING_ID, "12.50", "USD", "ACTIVE")));
        when(inventoryClient.get(LISTING_ID)).thenReturn(availability(LISTING_ID, BUSINESS_ID, 0));
        when(eligibilityClient.find(BUSINESS_ID, STORE_ID)).thenReturn(Optional.of(eligibility(false)));

        var response = service.validate();

        assertThat(response.checkoutReady()).isFalse();
        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("SELLER_UNAVAILABLE");
                    assertThat(item.issues()).extracting("code")
                            .containsExactly("CART_SELLER_UNAVAILABLE", "CART_OUT_OF_STOCK");
                    assertThat(item.issues()).extracting("action")
                            .containsOnly("REMOVE_ITEM");
                });
    }

    @Test
    void missingListingDoesNotCallSellerOrInventory() {
        when(repository.get(USER_ID)).thenReturn(cart(item(LISTING_ID, 1, "12.50", "USD")));
        when(productClient.find(LISTING_ID)).thenReturn(Optional.empty());

        var response = service.validate();

        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo("LISTING_UNAVAILABLE");
                    assertThat(item.issues()).extracting("code")
                            .containsExactly("CART_LISTING_UNAVAILABLE");
                });
        verifyNoInteractions(inventoryClient, eligibilityClient);
    }

    @Test
    void mixedCurrentCurrenciesMarkOtherwisePurchasableLines() {
        when(repository.get(USER_ID)).thenReturn(new CartDocument(
                3,
                NOW.plusSeconds(3600),
                List.of(
                        item(LISTING_ID, 1, "12.50", "USD"),
                        item(SECOND_LISTING_ID, 1, "10.00", "EUR"))));
        when(productClient.find(LISTING_ID))
                .thenReturn(Optional.of(product(LISTING_ID, "12.50", "USD", "ACTIVE")));
        when(productClient.find(SECOND_LISTING_ID))
                .thenReturn(Optional.of(product(SECOND_LISTING_ID, "10.00", "EUR", "ACTIVE")));
        when(inventoryClient.get(LISTING_ID)).thenReturn(availability(LISTING_ID, BUSINESS_ID, 4));
        when(inventoryClient.get(SECOND_LISTING_ID)).thenReturn(availability(SECOND_LISTING_ID, BUSINESS_ID, 4));
        when(eligibilityClient.find(BUSINESS_ID, STORE_ID)).thenReturn(Optional.of(eligibility(true)));

        var response = service.validate();

        assertThat(response.checkoutReady()).isFalse();
        assertThat(response.cartIssues()).extracting("code")
                .containsExactly("CART_CURRENCY_CONFLICT");
        assertThat(response.items()).extracting("status")
                .containsExactly("CURRENCY_CONFLICT", "CURRENCY_CONFLICT");
        verify(eligibilityClient).find(BUSINESS_ID, STORE_ID);
    }

    @Test
    void inventoryOwnershipMismatchFailsClosed() {
        when(repository.get(USER_ID)).thenReturn(cart(item(LISTING_ID, 1, "12.50", "USD")));
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product(LISTING_ID, "12.50", "USD", "ACTIVE")));
        when(inventoryClient.get(LISTING_ID))
                .thenReturn(availability(LISTING_ID, "01B00000000000000000000002", 4));
        when(eligibilityClient.find(BUSINESS_ID, STORE_ID)).thenReturn(Optional.of(eligibility(true)));

        var response = service.validate();

        assertThat(response.checkoutReady()).isFalse();
        assertThat(response.items()).singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("LISTING_UNAVAILABLE"));
    }

    @Test
    void dependencyFailureReturnsNoPartialValidation() {
        when(repository.get(USER_ID)).thenReturn(cart(item(LISTING_ID, 1, "12.50", "USD")));
        when(productClient.find(LISTING_ID)).thenThrow(new CartException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "CART_DEPENDENCY_UNAVAILABLE",
                "Product validation is temporarily unavailable."));

        assertThatThrownBy(service::validate)
                .isInstanceOf(CartException.class)
                .extracting(exception -> ((CartException) exception).code())
                .isEqualTo("CART_DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void emptyCartIsNotCheckoutReadyAndSkipsDependencies() {
        when(repository.get(USER_ID)).thenReturn(CartDocument.empty());

        var response = service.validate();

        assertThat(response.checkoutReady()).isFalse();
        assertThat(response.cartIssues()).extracting("code").containsExactly("CART_EMPTY");
        verifyNoInteractions(productClient, inventoryClient, eligibilityClient);
    }

    private CartDocument cart(CartStoredItem item) {
        return new CartDocument(3, NOW.plusSeconds(3600), List.of(item));
    }

    private CartStoredItem item(String listingId, int quantity, String price, String currency) {
        return new CartStoredItem(listingId, quantity, new BigDecimal(price), currency, NOW.minusSeconds(60));
    }

    private ProductCommerceClient.ProductContext product(
            String listingId,
            String price,
            String currency,
            String status) {
        return new ProductCommerceClient.ProductContext(
                listingId,
                BUSINESS_ID,
                STORE_ID,
                "BUSINESS",
                "Store item",
                new BigDecimal(price),
                currency,
                status,
                null);
    }

    private InventoryAvailabilityClient.Availability availability(
            String listingId,
            String businessId,
            int available) {
        return new InventoryAvailabilityClient.Availability(
                listingId,
                businessId,
                true,
                available,
                1);
    }

    private BusinessStoreEligibilityClient.Eligibility eligibility(boolean eligible) {
        return new BusinessStoreEligibilityClient.Eligibility(
                BUSINESS_ID,
                STORE_ID,
                eligible,
                eligible ? "ACTIVE" : "SUSPENDED",
                "ACTIVE");
    }
}
