package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.config.BusinessOrderProperties;
import com.msb.ecom.order_service.dto.BusinessOrderDetailResponse;
import com.msb.ecom.order_service.dto.BusinessOrderPageResponse;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.model.BusinessOrderView;
import com.msb.ecom.order_service.repository.BusinessOrderRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BusinessOrderServiceTests {

    private static final String BUSINESS_ID = id(10);
    private static final String BUSINESS_ORDER_ID = id(20);
    private static final Instant NOW = Instant.parse("2026-07-20T01:00:00Z");

    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final BusinessOrderAuthorizationClient authorization =
            mock(BusinessOrderAuthorizationClient.class);
    private final BusinessOrderRepository repository = mock(BusinessOrderRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private BusinessOrderService service;

    @BeforeEach
    void setUp() {
        service = service(true);
        when(actors.currentActor()).thenReturn(
                new CurrentActor("subject", "actor-token", "staff@example.test", "Staff", true));
        when(authorization.authorize("actor-token", BUSINESS_ID))
                .thenReturn(access(false));
    }

    @Test
    void disabledRoutesFailBeforeValidationActorAuthorizationOrRepositoryWork() {
        BusinessOrderService disabled = service(false);

        assertCode(() -> disabled.list("bad", "bad", "bad", "bad"),
                "BUSINESS_ORDERS_NOT_AVAILABLE");
        assertCode(() -> disabled.detail("bad", "bad"), "BUSINESS_ORDERS_NOT_AVAILABLE");

        verifyNoInteractions(actors, authorization, repository);
    }

    @Test
    void invalidIdsStatusCursorAndLimitFailBeforeAuthorizationOrRepositoryWork() {
        assertCode(() -> service.list("bad", null, null, null),
                "BUSINESS_ORDER_ID_INVALID");
        assertCode(() -> service.list(BUSINESS_ID, "pending_acceptance", null, null),
                "BUSINESS_ORDER_STATUS_INVALID");
        assertCode(() -> service.list(BUSINESS_ID, null, "not+base64", null),
                "BUSINESS_ORDER_CURSOR_INVALID");
        assertCode(
                () -> service.list(
                        BUSINESS_ID,
                        null,
                        cursor("not-an-instant", BUSINESS_ORDER_ID),
                        null),
                "BUSINESS_ORDER_CURSOR_INVALID");
        assertCode(() -> service.list(BUSINESS_ID, null, null, "51"),
                "BUSINESS_ORDER_LIMIT_INVALID");
        assertCode(() -> service.detail(BUSINESS_ID, "bad"), "BUSINESS_ORDER_ID_INVALID");

        verifyNoInteractions(actors, authorization, repository);
    }

    @Test
    void queueUsesLimitPlusOneStatusAndStableCursor() {
        List<BusinessOrderView> rows = List.of(
                order(20, NOW.plusSeconds(3), null),
                order(21, NOW.plusSeconds(2), null),
                order(22, NOW.plusSeconds(1), null));
        when(repository.findPage(
                BUSINESS_ID, "PENDING_ACCEPTANCE", null, null, 3, false))
                .thenReturn(rows);

        BusinessOrderPageResponse response =
                service.list(BUSINESS_ID, "PENDING_ACCEPTANCE", null, "2");

        assertThat(response.items())
                .extracting(BusinessOrderPageResponse.OrderSummary::businessOrderId)
                .containsExactly(id(20), id(21));
        assertThat(response.page().hasMore()).isTrue();
        BusinessOrderCursorCodec.Cursor cursor =
                BusinessOrderCursorCodec.decode(response.page().nextCursor());
        assertThat(cursor.createdAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(cursor.businessOrderId()).isEqualTo(id(21));
    }

    @Test
    void financeProjectionIsReturnedOnlyForFinanceAuthorizedReads() {
        when(authorization.authorize("actor-token", BUSINESS_ID)).thenReturn(access(true));
        when(repository.findPage(BUSINESS_ID, null, null, null, 21, true))
                .thenReturn(List.of(order(20, NOW, amount("2.5000"))));

        BusinessOrderPageResponse response =
                service.list(BUSINESS_ID, null, null, null);

        assertThat(response.items()).singleElement().satisfies(item ->
                assertThat(item.platformFeeProjection()).isEqualByComparingTo("2.5000"));
        verify(repository).findPage(BUSINESS_ID, null, null, null, 21, true);
    }

    @Test
    void detailReturnsOnlyOneBusinessGroupImmutableSnapshots() {
        when(repository.findOwnedDetail(BUSINESS_ID, BUSINESS_ORDER_ID, false))
                .thenReturn(Optional.of(order(20, NOW, null)));

        BusinessOrderDetailResponse response =
                service.detail(BUSINESS_ID, BUSINESS_ORDER_ID);

        assertThat(response.businessId()).isEqualTo(BUSINESS_ID);
        assertThat(response.paymentStatus()).isEqualTo("SUCCEEDED");
        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Persisted title");
            assertThat(item.policyVersion()).isEqualTo("LOCAL_DEMO_V1");
        });
        assertThat(response.shippingAddress().line1()).isEqualTo("1 Snapshot St");
    }

    @Test
    void missingAndCrossBusinessRowsShareTheSameNotFoundContract() {
        when(repository.findOwnedDetail(BUSINESS_ID, BUSINESS_ORDER_ID, false))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(BUSINESS_ID, BUSINESS_ORDER_ID))
                .isInstanceOfSatisfying(BusinessOrderException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("BUSINESS_ORDER_NOT_FOUND");
                    assertThat(exception.getMessage()).isEqualTo(
                            "Business order was not found.");
                });
    }

    @Test
    void authorizationDenialStopsBeforeRepositoryAccess() {
        when(authorization.authorize(anyString(), eq(BUSINESS_ID)))
                .thenThrow(new BusinessOrderException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "BUSINESS_ORDER_NOT_FOUND",
                        "Business order was not found."));

        assertCode(() -> service.list(BUSINESS_ID, null, null, null),
                "BUSINESS_ORDER_NOT_FOUND");

        verifyNoInteractions(repository);
    }

    @Test
    void emptyQueueHasNoCursor() {
        when(repository.findPage(eq(BUSINESS_ID), any(), any(), any(), anyInt(), eq(false)))
                .thenReturn(List.of());

        BusinessOrderPageResponse response =
                service.list(BUSINESS_ID, null, null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.page().hasMore()).isFalse();
        assertThat(response.page().nextCursor()).isNull();
    }

    private BusinessOrderService service(boolean enabled) {
        return new BusinessOrderService(
                new BusinessOrderProperties(enabled),
                actors,
                authorization,
                repository,
                new BusinessOrderMetrics(meters));
    }

    private BusinessOrderAuthorizationClient.Access access(boolean finance) {
        return new BusinessOrderAuthorizationClient.Access(
                BUSINESS_ID, id(99), "MANAGER", finance);
    }

    private BusinessOrderView order(int value, Instant createdAt, BigDecimal finance) {
        return new BusinessOrderView(
                id(value),
                id(100 + value),
                BUSINESS_ID,
                id(11),
                "PENDING_ACCEPTANCE",
                "NONE",
                id(30),
                id(130),
                "SUCCEEDED",
                1,
                2,
                amount("25.0000"),
                amount("25.0000"),
                "USD",
                finance,
                createdAt,
                createdAt,
                createdAt,
                List.of(new BusinessOrderView.Item(
                        id(40),
                        "Persisted title",
                        "SKU-1",
                        "NEW",
                        "https://example.test/item.jpg",
                        amount("12.5000"),
                        "USD",
                        2,
                        amount("25.0000"),
                        "LOCAL_DEMO_V1")),
                new BusinessOrderView.Address(
                        "Snapshot Buyer",
                        "+15550123456",
                        "1 Snapshot St",
                        null,
                        "Irvine",
                        "CA",
                        "92618",
                        "US"));
    }

    private void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        BusinessOrderException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static String cursor(String timestamp, String businessOrderId) {
        String raw = "v1\t" + timestamp + "\t" + businessOrderId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
