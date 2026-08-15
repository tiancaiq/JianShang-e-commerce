package com.msb.ecom.order_service;

import com.msb.ecom.common.web.autoconfigure.CommonWebAutoConfiguration;
import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.OrderCancellationController;
import com.msb.ecom.order_service.dto.OrderCancellationResponse;
import com.msb.ecom.order_service.model.BuyerOrderException;
import com.msb.ecom.order_service.service.OrderCancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderCancellationController.class)
@Import({SecurityConfig.class, CommonWebAutoConfiguration.class})
class OrderCancellationControllerTests {

    private static final String ORDER_ID = id(1);

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    OrderCancellationService service;

    @Test
    void routeRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancellation-requests", ORDER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noBodyCommandReturnsBoundedResponseEtagAndNoStore() throws Exception {
        Instant requestedAt = Instant.parse("2026-07-20T02:00:00Z");
        when(service.request(
                ORDER_ID, "\"0\"", "cancel-key-001", false, "cancel-correlation"))
                .thenReturn(new OrderCancellationResponse(
                        ORDER_ID,
                        id(2),
                        "CANCELLATION_REQUESTED",
                        "PENDING",
                        1,
                        requestedAt));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancellation-requests", ORDER_ID)
                        .with(jwt())
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "cancel-key-001")
                        .header("X-Correlation-Id", "cancel-correlation"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.cancellationRequestId").value(id(2)))
                .andExpect(jsonPath("$.status").value("CANCELLATION_REQUESTED"))
                .andExpect(jsonPath("$.requestStatus").value("PENDING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.requestedAt").value("2026-07-20T02:00:00Z"))
                .andExpect(jsonPath("$.buyerId").doesNotExist())
                .andExpect(jsonPath("$.paymentIntentId").doesNotExist())
                .andExpect(jsonPath("$.businessIds").doesNotExist());
    }

    @Test
    void emptyChunkedCommandRemainsBodyless() throws Exception {
        Instant requestedAt = Instant.parse("2026-07-20T02:00:00Z");
        when(service.request(
                ORDER_ID, "\"0\"", "cancel-key-002", false, "chunked-correlation"))
                .thenReturn(new OrderCancellationResponse(
                        ORDER_ID, id(2), "CANCELLATION_REQUESTED", "PENDING", 1, requestedAt));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancellation-requests", ORDER_ID)
                        .with(jwt())
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "cancel-key-002")
                        .header("X-Correlation-Id", "chunked-correlation")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());

        verify(service).request(
                ORDER_ID, "\"0\"", "cancel-key-002", false, "chunked-correlation");
    }

    @Test
    void bodyPresenceIsForwardedForStrictServiceValidation() throws Exception {
        when(service.request(
                ORDER_ID, "0", "cancel-key-001", true, "cancel-correlation"))
                .thenThrow(new BuyerOrderException(
                        HttpStatus.BAD_REQUEST,
                        "ORDER_CANCELLATION_BODY_NOT_ALLOWED",
                        "The cancellation request must not include a body."));

        mockMvc.perform(post("/api/v1/orders/{orderId}/cancellation-requests", ORDER_ID)
                        .with(jwt())
                        .header("If-Match", "0")
                        .header("Idempotency-Key", "cancel-key-001")
                        .header("X-Correlation-Id", "cancel-correlation")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("ORDER_CANCELLATION_BODY_NOT_ALLOWED"))
                .andExpect(jsonPath("$.error.correlationId").value("cancel-correlation"));

        verify(service).request(
                ORDER_ID, "0", "cancel-key-001", true, "cancel-correlation");
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
