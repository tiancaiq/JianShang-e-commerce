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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CartServiceTests {

    private static final String USER_ID = "01U00000000000000000000001";
    private static final String LISTING_ID = "01L00000000000000000000001";
    private static final String BUSINESS_ID = "01B00000000000000000000001";
    private static final Instant NOW = Instant.parse("2026-07-17T10:00:00Z");

    private CurrentActorProvider actorProvider;
    private CartRepository repository;
    private ProductCommerceClient productClient;
    private InventoryAvailabilityClient inventoryClient;
    private CartService service;

    @BeforeEach
    void setUp() {
        actorProvider = mock(CurrentActorProvider.class);
        repository = mock(CartRepository.class);
        productClient = mock(ProductCommerceClient.class);
        inventoryClient = mock(InventoryAvailabilityClient.class);
        when(actorProvider.currentActor()).thenReturn(new CurrentActor(
                USER_ID,
                "buyer-token",
                "buyer@example.test",
                "Buyer",
                true));
        service = new CartService(
                actorProvider,
                repository,
                productClient,
                inventoryClient,
                Clock.fixed(NOW, ZoneOffset.UTC),
                999);
    }

    @Test
    void addUsesJwtSubjectAndStoresObservedCatalogPrice() {
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product("ACTIVE")));
        when(inventoryClient.get(LISTING_ID)).thenReturn(availability(4));
        when(repository.upsert(eq(USER_ID), any())).thenReturn(new CartDocument(
                1,
                NOW.plusSeconds(3600),
                List.of(new CartStoredItem(LISTING_ID, 2, new BigDecimal("12.50"), "USD", NOW))));

        var response = service.add(LISTING_ID, 2);

        assertThat(response.totalQuantity()).isEqualTo(2);
        assertThat(response.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.storeName()).isEqualTo("Acme Trading Store");
                    assertThat(item.storeSlug()).isEqualTo("acme-trading-store");
                    assertThat(item.businessVerified()).isTrue();
                    assertThat(item.publicCity()).isEqualTo("Irvine");
                    assertThat(item.publicRegion()).isEqualTo("CA");
                });
        assertThat(response.totals()).singleElement()
                .satisfies(total -> {
                    assertThat(total.currency()).isEqualTo("USD");
                    assertThat(total.amount()).isEqualByComparingTo("25.00");
                });
        verify(repository).upsert(eq(USER_ID), eq(new CartStoredItem(
                LISTING_ID,
                2,
                new BigDecimal("12.50"),
                "USD",
                NOW)));
    }

    @Test
    void addRejectsNonActiveOrMissingBusinessListing() {
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product("PAUSED")));

        assertThatThrownBy(() -> service.add(LISTING_ID, 1))
                .isInstanceOf(CartException.class)
                .extracting(exception -> ((CartException) exception).code())
                .isEqualTo("CART_ITEM_NOT_ELIGIBLE");
        verify(repository, never()).upsert(any(), any());
    }

    @Test
    void addRejectsQuantityAboveAuthoritativeAvailability() {
        when(productClient.find(LISTING_ID)).thenReturn(Optional.of(product("ACTIVE")));
        when(inventoryClient.get(LISTING_ID)).thenReturn(availability(1));

        assertThatThrownBy(() -> service.add(LISTING_ID, 2))
                .isInstanceOf(CartException.class)
                .extracting(exception -> ((CartException) exception).code())
                .isEqualTo("CART_INSUFFICIENT_STOCK");
        verify(repository, never()).upsert(any(), any());
    }

    @Test
    void getReadsOnlyTheAuthenticatedUsersCart() {
        when(repository.get(USER_ID)).thenReturn(CartDocument.empty());

        var response = service.get();

        assertThat(response.itemCount()).isZero();
        verify(repository).get(USER_ID);
    }

    private ProductCommerceClient.ProductContext product(String status) {
        return new ProductCommerceClient.ProductContext(
                LISTING_ID,
                BUSINESS_ID,
                "01S00000000000000000000001",
                "BUSINESS",
                "Store item",
                "SKU-STORE-ITEM",
                "NEW",
                7,
                new BigDecimal("12.50"),
                "USD",
                status,
                null,
                "Acme Trading Store",
                "acme-trading-store",
                true,
                "Irvine",
                "CA");
    }

    private InventoryAvailabilityClient.Availability availability(int available) {
        return new InventoryAvailabilityClient.Availability(
                LISTING_ID,
                BUSINESS_ID,
                true,
                available,
                1);
    }
}
