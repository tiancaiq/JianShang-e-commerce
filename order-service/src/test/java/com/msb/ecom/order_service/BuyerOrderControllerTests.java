package com.msb.ecom.order_service;

import com.msb.ecom.common.web.autoconfigure.CommonWebAutoConfiguration;
import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.BuyerOrderController;
import com.msb.ecom.order_service.dto.BuyerOrderDetailResponse;
import com.msb.ecom.order_service.dto.BuyerOrderPageResponse;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.service.BuyerOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BuyerOrderController.class)
@Import({SecurityConfig.class, CommonWebAutoConfiguration.class})
class BuyerOrderControllerTests {

    private static final String ORDER_ID = id(1);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BuyerOrderService service;

    @Test
    void buyerOrderRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listForwardsCursorAndLimitAndReturnsOnlyApprovedSummaryFields() throws Exception {
        when(service.list(anyString(), anyString())).thenReturn(page());

        String body = mockMvc.perform(get("/api/v1/orders")
                        .with(jwt())
                        .param("cursor", "opaque-cursor")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].groups[0].businessId").value(id(3)))
                .andExpect(jsonPath("$.page.hasMore").value(false))
                .andReturn().getResponse().getContentAsString();

        verify(service).list("opaque-cursor", "10");
        assertThat(body)
                .doesNotContain(
                        "paymentIntentId",
                        "provider",
                        "eventId",
                        "payloadHash",
                        "lease",
                        "outbox",
                        "history",
                        "sellerOrderNumber",
                        "shipment",
                        "version");
    }

    @Test
    void detailReturnsImmutableBuyerSnapshotsWithoutInternalFields() throws Exception {
        when(service.detail(ORDER_ID)).thenReturn(detail());

        String body = mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].storeId").value(id(4)))
                .andExpect(jsonPath("$.groups[0].items[0].listingId").value(id(5)))
                .andExpect(jsonPath("$.groups[0].items[0].policyVersion")
                        .value("LOCAL_DEMO_V1"))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("Buyer"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain(
                        "paymentIntentId",
                        "providerReference",
                        "sourceAddressId",
                        "sourceVersion",
                        "catalogVersion",
                        "sellerOrderNumber",
                        "platformFee",
                        "shipment",
                        "\"version\"");
    }

    @Test
    void errorsUseStandardEnvelopeAndCorrelationId() throws Exception {
        when(service.detail(ORDER_ID)).thenThrow(new BuyerOrderException(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "Order was not found."));

        mockMvc.perform(get("/api/v1/orders/{orderId}", ORDER_ID)
                        .with(jwt())
                        .header("X-Correlation-Id", "order-read-correlation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.correlationId").value("order-read-correlation"));
    }

    @Test
    void nonNumericLimitUsesTheStableOrderLimitError() throws Exception {
        when(service.list(any(), eq("many"))).thenThrow(new BuyerOrderException(
                HttpStatus.BAD_REQUEST,
                "ORDER_LIMIT_INVALID",
                "Limit must be from 1 through 50."));

        mockMvc.perform(get("/api/v1/orders")
                        .with(jwt())
                        .param("limit", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ORDER_LIMIT_INVALID"));
    }

    private BuyerOrderPageResponse page() {
        Instant now = Instant.parse("2026-07-20T01:00:00Z");
        return new BuyerOrderPageResponse(
                List.of(new BuyerOrderPageResponse.OrderSummary(
                        ORDER_ID,
                        "CONFIRMED",
                        "SUCCEEDED",
                        amount("25.0000"),
                        "USD",
                        now,
                        now,
                        List.of(new BuyerOrderPageResponse.GroupSummary(
                                id(2), id(3), "PENDING_ACCEPTANCE", amount("25.0000"), "USD")))),
                new BuyerOrderPageResponse.PageMetadata(null, false));
    }

    private BuyerOrderDetailResponse detail() {
        Instant now = Instant.parse("2026-07-20T01:00:00Z");
        return new BuyerOrderDetailResponse(
                ORDER_ID,
                "CONFIRMED",
                "SUCCEEDED",
                amount("25.0000"),
                "USD",
                now,
                now,
                List.of(new BuyerOrderDetailResponse.Group(
                        id(2),
                        id(3),
                        id(4),
                        "PENDING_ACCEPTANCE",
                        amount("25.0000"),
                        "USD",
                        List.of(new BuyerOrderDetailResponse.Item(
                                id(5),
                                "Snapshot title",
                                id(3),
                                id(4),
                                amount("25.0000"),
                                "USD",
                                1,
                                amount("25.0000"),
                                "LOCAL_DEMO_V1")))),
                new BuyerOrderDetailResponse.ShippingAddress(
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

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
