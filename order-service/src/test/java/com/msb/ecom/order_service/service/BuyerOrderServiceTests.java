package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BuyerOrderProperties;
import com.msb.ecom.order_service.dto.BuyerOrderDetailResponse;
import com.msb.ecom.order_service.dto.BuyerOrderPageResponse;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.model.BuyerOrderView;
import com.msb.ecom.order_service.repository.BuyerOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuyerOrderServiceTests {

    private static final String SUBJECT = "buyer-subject";
    private static final String BUYER_ID = id(90);
    private static final String ORDER_ID = id(1);
    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");

    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final BuyerIdentityClient buyers = mock(BuyerIdentityClient.class);
    private final BuyerOrderRepository repository = mock(BuyerOrderRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private BuyerOrderService service;

    @BeforeEach
    void setUp() {
        service = service(true);
        when(actors.currentActor()).thenReturn(
                new CurrentActor(SUBJECT, "token", "buyer@example.test", "Buyer", true));
        when(buyers.resolveBuyer(SUBJECT)).thenReturn(BUYER_ID);
    }

    @Test
    void disabledReadsFailBeforeActorIdentityOrRepositoryAccess() {
        BuyerOrderService disabled = service(false);

        assertCode(() -> disabled.list("malformed", "many"), "ORDERS_NOT_AVAILABLE");
        assertCode(() -> disabled.detail(ORDER_ID), "ORDERS_NOT_AVAILABLE");

        verifyNoInteractions(actors, buyers, repository);
        assertThat(meters.counter(
                "buyer.orders.reads", "operation", "list", "result", "disabled").count())
                .isEqualTo(1);
        assertThat(meters.counter(
                "buyer.orders.reads", "operation", "detail", "result", "disabled").count())
                .isEqualTo(1);
    }

    @Test
    void malformedCursorLimitAndOrderIdFailBeforeIdentityOrRepositoryAccess() {
        assertCode(() -> service.list("not+url-base64", null), "ORDER_CURSOR_INVALID");
        assertCode(() -> service.list(null, "many"), "ORDER_LIMIT_INVALID");
        assertCode(() -> service.list(null, "0"), "ORDER_LIMIT_INVALID");
        assertCode(() -> service.list(null, "51"), "ORDER_LIMIT_INVALID");
        assertCode(() -> service.detail("bad-id"), "ORDER_ID_INVALID");

        verifyNoInteractions(actors, buyers, repository);
    }

    @Test
    void listUsesLimitPlusOneAndBuildsStableVersionedCursor() {
        List<BuyerOrderView> rows = List.of(
                order(1, NOW.plusSeconds(3)),
                order(2, NOW.plusSeconds(2)),
                order(3, NOW.plusSeconds(1)));
        when(repository.findPage(BUYER_ID, null, null, 3)).thenReturn(rows);

        BuyerOrderPageResponse response = service.list(null, "2");

        assertThat(response.items()).extracting(BuyerOrderPageResponse.OrderSummary::orderId)
                .containsExactly(id(1), id(2));
        assertThat(response.page().hasMore()).isTrue();
        BuyerOrderCursorCodec.Cursor cursor =
                BuyerOrderCursorCodec.decode(response.page().nextCursor());
        assertThat(cursor.createdAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(cursor.orderId()).isEqualTo(id(2));
        verify(repository).findPage(BUYER_ID, null, null, 3);
    }

    @Test
    void detailMapsOnlyApprovedSnapshots() {
        when(repository.findOwnedDetail(BUYER_ID, ORDER_ID))
                .thenReturn(Optional.of(order(1, NOW)));

        BuyerOrderDetailResponse response = service.detail(ORDER_ID);

        assertThat(response.orderId()).isEqualTo(ORDER_ID);
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(response.groups()).singleElement().satisfies(group -> {
            assertThat(group.businessId()).isEqualTo(id(20));
            assertThat(group.storeId()).isEqualTo(id(21));
            assertThat(group.items()).singleElement().satisfies(item -> {
                assertThat(item.title()).isEqualTo("Snapshot title 1");
                assertThat(item.policyVersion()).isEqualTo("LOCAL_DEMO_V1");
            });
        });
        assertThat(response.shippingAddress().line1()).isEqualTo("1 Main St");
    }

    @Test
    void missingAndCrossBuyerRepositoryOutcomesShareOneNotFoundContract() {
        when(repository.findOwnedDetail(BUYER_ID, ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(ORDER_ID))
                .isInstanceOfSatisfying(BuyerOrderException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("ORDER_NOT_FOUND");
                    assertThat(exception.getMessage()).isEqualTo("Order was not found.");
                });
    }

    @Test
    void emptyListReturnsNoCursor() {
        when(repository.findPage(eq(BUYER_ID), any(), any(), anyInt()))
                .thenReturn(List.of());

        BuyerOrderPageResponse response = service.list(null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.page().hasMore()).isFalse();
        assertThat(response.page().nextCursor()).isNull();
        verify(repository).findPage(BUYER_ID, null, null, 21);
    }

    private BuyerOrderService service(boolean enabled) {
        return new BuyerOrderService(
                new BuyerOrderProperties(enabled),
                actors,
                buyers,
                repository,
                new BuyerOrderMetrics(meters));
    }

    private BuyerOrderView order(int value, Instant createdAt) {
        String orderId = id(value);
        return new BuyerOrderView(
                orderId,
                "CONFIRMED",
                "SUCCEEDED",
                amount("25.0000"),
                "USD",
                createdAt,
                createdAt,
                List.of(new BuyerOrderView.Group(
                        id(10 + value),
                        id(20),
                        id(21),
                        "PENDING_ACCEPTANCE",
                        amount("25.0000"),
                        List.of(new BuyerOrderView.Item(
                                id(30 + value),
                                "Snapshot title " + value,
                                id(20),
                                id(21),
                                amount("25.0000"),
                                "USD",
                                1,
                                amount("25.0000"),
                                "LOCAL_DEMO_V1")))),
                new BuyerOrderView.Address(
                        "Home",
                        "Buyer",
                        "+15550123456",
                        "1 Main St",
                        null,
                        "Irvine",
                        "CA",
                        "92618",
                        "US"));
    }

    private void assertCode(Runnable command, String code) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        BuyerOrderException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
