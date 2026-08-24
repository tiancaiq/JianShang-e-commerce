package com.msb.ecom.order_service;

import com.msb.ecom.common.web.autoconfigure.CommonWebAutoConfiguration;
import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.AdminOrderController;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationPreview;
import com.msb.ecom.order_service.dto.AdminOrderContracts.CancellationResult;
import com.msb.ecom.order_service.dto.AdminOrderContracts.Page;
import com.msb.ecom.order_service.dto.AdminOrderContracts.Summary;
import com.msb.ecom.order_service.model.AdminOrderException;
import com.msb.ecom.order_service.service.AdminOrderCancellationService;
import com.msb.ecom.order_service.service.AdminOrderService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminOrderController.class)
@Import({SecurityConfig.class, CommonWebAutoConfiguration.class})
class AdminOrderControllerTests {
    private static final String ORDER_ID = id(1);
    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminOrderService orders;
    @MockitoBean AdminOrderCancellationService cancellation;

    @Test
    void routesRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/orders/{id}", ORDER_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/orders/{id}/cancel/dry-run", ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void searchForwardsServerSideFiltersAndDoesNotExposeQueuePii() throws Exception {
        when(orders.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Integer.class), any(Integer.class), anyString())).thenReturn(page());
        String body = mockMvc.perform(get("/api/v1/admin/orders").with(jwt())
                        .param("businessId", id(3)).param("status", "CONFIRMED")
                        .param("page", "2").param("size", "10").param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.content[0].businessIds[0]").value(id(3)))
                .andExpect(jsonPath("$.page").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("shippingAddress", "recipientName", "phone", "providerPaymentReference");
    }

    @Test
    void dryRunAndExecutionUseBodyVersionAndStableResponseEtag() throws Exception {
        when(cancellation.preview(anyString(), any())).thenReturn(new CancellationPreview(
                ORDER_ID, "CONFIRMED", 4, true, "CANCELLATION_REQUESTED", "Restock", "Refund",
                "No account change", "No enforcement change", List.of(), null, null));
        when(cancellation.execute(anyString(), any(), anyString())).thenReturn(new CancellationResult(
                ORDER_ID, id(9), "CANCELLATION_REQUESTED", 5, NOW, false));
        String json = """
                {"reasonCode":"FRAUD_PREVENTION","reason":"Risk review",
                 "expectedOrderVersion":4,"idempotencyKey":"admin-order-key-1"}
                """;
        mockMvc.perform(post("/api/v1/admin/orders/{id}/cancel/dry-run", ORDER_ID).with(jwt())
                        .contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        mockMvc.perform(post("/api/v1/admin/orders/{id}/cancel", ORDER_ID).with(jwt())
                        .header("X-Correlation-Id", "admin-order-correlation")
                        .contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.status").value("CANCELLATION_REQUESTED"));
        verify(cancellation).execute(anyString(), any(), anyString());
    }

    @Test
    void adminErrorsUseTheStandardEnvelope() throws Exception {
        when(cancellation.preview(anyString(), any())).thenThrow(new AdminOrderException(
                HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_FINANCIAL_STATE_UNSUPPORTED", "Refund unavailable."));
        mockMvc.perform(post("/api/v1/admin/orders/{id}/cancel/dry-run", ORDER_ID).with(jwt())
                        .contentType("application/json")
                        .content("{\"reasonCode\":\"OTHER\",\"reason\":\"Test\",\"expectedOrderVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("ORDER_FINANCIAL_STATE_UNSUPPORTED"));
    }

    private Page page() {
        return new Page(List.of(new Summary(ORDER_ID, id(2), "Buyer", "BUSINESS", List.of(id(3)),
                List.of("Store"), "CONFIRMED", "SUCCEEDED", "PENDING_ACCEPTANCE",
                new BigDecimal("25.0000"), "USD", 1, NOW, NOW, 0)), 2, 10, 21, 3, "createdAt,asc");
    }

    private static String id(int value) { return "01" + String.format("%024d", value); }
}
