package com.msb.ecom.order_service;

import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.CheckoutController;
import com.msb.ecom.order_service.dto.CheckoutPaymentIntentResponse;
import com.msb.ecom.order_service.dto.CheckoutResponse;
import com.msb.ecom.order_service.service.CheckoutPaymentService;
import com.msb.ecom.order_service.service.CheckoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = CheckoutController.class,
        properties = "spring.jackson.deserialization.fail-on-unknown-properties=true")
@Import(SecurityConfig.class)
class CheckoutControllerTests {

    private static final String CHECKOUT_ID = "01C00000000000000000000001";
    private static final String ADDRESS_ID = "01A00000000000000000000001";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CheckoutService checkoutService;

    @MockitoBean
    CheckoutPaymentService checkoutPaymentService;

    @Test
    void checkoutRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/checkouts/{id}", CHECKOUT_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRejectsUnknownClientOwnedFields() throws Exception {
        mockMvc.perform(post("/api/v1/checkouts")
                        .with(jwt())
                        .header("Idempotency-Key", "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cartVersion": 3,
                                  "addressId": "%s",
                                  "total": 0
                                }
                                """.formatted(ADDRESS_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHECKOUT_INVALID_REQUEST"));
    }

    @Test
    void createForwardsIdempotencyKeyAndReturnsCreated() throws Exception {
        when(checkoutService.create(anyString(), any(), any())).thenReturn(response("PENDING_PAYMENT"));

        mockMvc.perform(post("/api/v1/checkouts")
                        .with(jwt())
                        .header("Idempotency-Key", "create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cartVersion":3,"addressId":"%s"}
                                """.formatted(ADDRESS_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CHECKOUT_ID))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

        verify(checkoutService).create(anyString(), any(), any());
    }

    @Test
    void cancelForwardsCommandKey() throws Exception {
        when(checkoutService.cancel(anyString(), anyString(), any())).thenReturn(response("CANCELLED"));

        mockMvc.perform(post("/api/v1/checkouts/{id}/cancel", CHECKOUT_ID)
                        .with(jwt())
                        .header("Idempotency-Key", "cancel-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void paymentIntentRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/checkouts/{id}/payment-intent", CHECKOUT_ID)
                        .header("Idempotency-Key", "payment-key-001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentIntentForwardsOriginalKeyAndReturnsOnlyStableBuyerContract() throws Exception {
        when(checkoutPaymentService.create(anyString(), anyString(), any()))
                .thenReturn(paymentResponse());

        mockMvc.perform(post("/api/v1/checkouts/{id}/payment-intent", CHECKOUT_ID)
                        .with(jwt())
                        .header("Idempotency-Key", "payment-key-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("01P00000000000000000000001"))
                .andExpect(jsonPath("$.checkoutId").value(CHECKOUT_ID))
                .andExpect(jsonPath("$.status").value("REQUIRES_ACTION"))
                .andExpect(jsonPath("$.action.type").value("FAKE_HOSTED_ACTION"))
                .andExpect(jsonPath("$.buyerId").doesNotExist())
                .andExpect(jsonPath("$.businessIds").doesNotExist())
                .andExpect(jsonPath("$.providerReference").doesNotExist());

        verify(checkoutPaymentService).create(
                org.mockito.ArgumentMatchers.eq(CHECKOUT_ID),
                org.mockito.ArgumentMatchers.eq("payment-key-001"),
                any());
    }

    private CheckoutResponse response(String status) {
        Instant now = Instant.parse("2026-07-19T12:00:00Z");
        BigDecimal zero = new BigDecimal("0.0000");
        return new CheckoutResponse(
                CHECKOUT_ID,
                status,
                3,
                "USD",
                zero,
                zero,
                zero,
                zero,
                zero,
                now.plusSeconds(900),
                new CheckoutResponse.Reservation(null, null, "NOT_REQUIRED"),
                new CheckoutResponse.Address(
                        ADDRESS_ID, 0, "Home", "Buyer", "+15550123456",
                        "1 Main St", null, "Irvine", "CA", "92618", "US"),
                List.of(),
                List.of(),
                new CheckoutResponse.TaxQuote(zero, "ZERO_LOCAL_DEMO_V1"),
                List.of(),
                null,
                now,
                now);
    }

    private CheckoutPaymentIntentResponse paymentResponse() {
        return new CheckoutPaymentIntentResponse(
                "01P00000000000000000000001",
                CHECKOUT_ID,
                "REQUIRES_ACTION",
                1,
                new BigDecimal("25.0000"),
                "USD",
                Instant.parse("2026-07-19T12:15:00Z"),
                new CheckoutPaymentIntentResponse.ProviderAction(
                        "FAKE_HOSTED_ACTION",
                        "fake_action_reference"),
                null);
    }
}
