package com.msb.ecom.order_service;

import com.msb.ecom.common.web.autoconfigure.CommonWebAutoConfiguration;
import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.BusinessOrderController;
import com.msb.ecom.order_service.dto.BusinessOrderAcceptanceResponse;
import com.msb.ecom.order_service.dto.BusinessOrderDetailResponse;
import com.msb.ecom.order_service.dto.BusinessOrderPageResponse;
import com.msb.ecom.order_service.model.BusinessOrderException;
import com.msb.ecom.order_service.service.BusinessOrderAcceptanceService;
import com.msb.ecom.order_service.service.BusinessOrderFulfillmentService;
import com.msb.ecom.order_service.service.BusinessOrderService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BusinessOrderController.class)
@Import({SecurityConfig.class, CommonWebAutoConfiguration.class})
class BusinessOrderControllerTests {

    private static final String BUSINESS_ID = id(1);
    private static final String BUSINESS_ORDER_ID = id(2);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    BusinessOrderService service;

    @MockitoBean
    BusinessOrderAcceptanceService acceptanceService;

    @MockitoBean
    BusinessOrderFulfillmentService fulfillmentService;

    @Test
    void businessRoutesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/{businessId}/orders", BUSINESS_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(
                        "/api/v1/businesses/{businessId}/orders/{businessOrderId}",
                        BUSINESS_ID,
                        BUSINESS_ORDER_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(
                        "/api/v1/businesses/{businessId}/orders/{businessOrderId}/accept",
                        BUSINESS_ID,
                        BUSINESS_ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listForwardsBoundedInputsAndOmitsAddressAndInternalFields() throws Exception {
        when(service.list(BUSINESS_ID, "PENDING_ACCEPTANCE", "opaque", "10"))
                .thenReturn(page(null));

        String body = mockMvc.perform(get("/api/v1/businesses/{businessId}/orders", BUSINESS_ID)
                        .with(jwt())
                        .param("status", "PENDING_ACCEPTANCE")
                        .param("cursor", "opaque")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].businessOrderId").value(BUSINESS_ORDER_ID))
                .andExpect(jsonPath("$.items[0].status").value("PENDING_ACCEPTANCE"))
                .andExpect(jsonPath("$.items[0].itemCount").value(1))
                .andExpect(jsonPath("$.items[0].platformFeeProjection").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        verify(service).list(BUSINESS_ID, "PENDING_ACCEPTANCE", "opaque", "10");
        assertThat(body).doesNotContain(
                "shippingAddress",
                "recipientName",
                "paymentIntent",
                "provider",
                "eventId",
                "payloadHash",
                "lease",
                "outbox",
                "idempotency",
                "sourceAddressId",
                "sourceVersion",
                "history");
    }

    @Test
    void detailReturnsMinimumFulfillmentSnapshotAndPermittedFinanceProjection()
            throws Exception {
        when(service.detail(BUSINESS_ID, BUSINESS_ORDER_ID))
                .thenReturn(detail(amount("2.5000")));

        String body = mockMvc.perform(get(
                        "/api/v1/businesses/{businessId}/orders/{businessOrderId}",
                        BUSINESS_ID,
                        BUSINESS_ORDER_ID)
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("Persisted title"))
                .andExpect(jsonPath("$.items[0].policyVersion").value("LOCAL_DEMO_V1"))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("Snapshot Buyer"))
                .andExpect(jsonPath("$.platformFeeProjection").value(2.5))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(
                "buyerId",
                "email",
                "label",
                "catalogVersion",
                "paymentIntent",
                "provider",
                "eventId",
                "payloadHash",
                "lease",
                "outbox",
                "sourceAddressId",
                "sourceVersion",
                "shipment");
    }

    @Test
    void denialsUseStandardNonEnumeratingEnvelopeAndCorrelationId() throws Exception {
        when(service.detail(BUSINESS_ID, BUSINESS_ORDER_ID))
                .thenThrow(new BusinessOrderException(
                        HttpStatus.NOT_FOUND,
                        "BUSINESS_ORDER_NOT_FOUND",
                        "Business order was not found."));

        mockMvc.perform(get(
                        "/api/v1/businesses/{businessId}/orders/{businessOrderId}",
                        BUSINESS_ID,
                        BUSINESS_ORDER_ID)
                        .with(jwt())
                        .header("X-Correlation-Id", "business-order-correlation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.correlationId")
                        .value("business-order-correlation"));
    }

    @Test
    void acceptForwardsHeadersAndReturnsOnlyApprovedBodyAndQuotedEtag()
            throws Exception {
        when(acceptanceService.accept(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new BusinessOrderAcceptanceResponse(
                        BUSINESS_ORDER_ID,
                        "ACCEPTED",
                        1,
                        now()));

        String body = mockMvc.perform(post(
                        "/api/v1/businesses/{businessId}/orders/{businessOrderId}/accept",
                        BUSINESS_ID,
                        BUSINESS_ORDER_ID)
                        .with(jwt())
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "accept-key-001")
                        .header("X-Correlation-Id", "accept-correlation"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.businessOrderId").value(BUSINESS_ORDER_ID))
                .andExpect(jsonPath("$.fulfillmentStatus").value("ACCEPTED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.updatedAt").value(now().toString()))
                .andReturn().getResponse().getContentAsString();

        verify(acceptanceService).accept(
                BUSINESS_ID,
                BUSINESS_ORDER_ID,
                "\"0\"",
                "accept-key-001",
                "accept-correlation");
        assertThat(body).doesNotContain(
                "buyer",
                "orderId",
                "payment",
                "provider",
                "address",
                "actor",
                "idempotency",
                "history",
                "outbox");
    }

    private BusinessOrderPageResponse page(BigDecimal finance) {
        return new BusinessOrderPageResponse(
                List.of(summary(finance)),
                new BusinessOrderPageResponse.PageMetadata(null, false));
    }

    private BusinessOrderPageResponse.OrderSummary summary(BigDecimal finance) {
        return new BusinessOrderPageResponse.OrderSummary(
                BUSINESS_ORDER_ID,
                id(3),
                BUSINESS_ID,
                id(4),
                "PENDING_ACCEPTANCE",
                "NONE",
                id(5),
                id(6),
                1,
                2,
                amount("25.0000"),
                amount("25.0000"),
                "USD",
                finance,
                now(),
                now(),
                now());
    }

    private BusinessOrderDetailResponse detail(BigDecimal finance) {
        BusinessOrderPageResponse.OrderSummary summary = summary(finance);
        return new BusinessOrderDetailResponse(
                summary.businessOrderId(),
                summary.sellerOrderNumber(),
                summary.businessId(),
                summary.storeId(),
                summary.status(),
                summary.cancellationStatus(),
                summary.buyerOrderId(),
                summary.buyerOrderNumber(),
                "SUCCEEDED",
                summary.itemCount(),
                summary.totalQuantity(),
                summary.subtotal(),
                summary.totalAmount(),
                summary.currency(),
                summary.platformFeeProjection(),
                summary.confirmedAt(),
                summary.createdAt(),
                summary.updatedAt(),
                List.of(new BusinessOrderDetailResponse.Item(
                        id(7),
                        "Persisted title",
                        "SKU-1",
                        "NEW",
                        null,
                        amount("12.5000"),
                        "USD",
                        2,
                        amount("25.0000"),
                        "LOCAL_DEMO_V1")),
                new BusinessOrderDetailResponse.ShippingAddress(
                        "Snapshot Buyer",
                        "+15550123456",
                        "1 Snapshot St",
                        null,
                        "Irvine",
                        "CA",
                        "92618",
                        "US"));
    }

    private static Instant now() {
        return Instant.parse("2026-07-20T01:00:00Z");
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
