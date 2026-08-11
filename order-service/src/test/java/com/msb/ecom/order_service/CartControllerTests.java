package com.msb.ecom.order_service;

import com.msb.ecom.order_service.config.SecurityConfig;
import com.msb.ecom.order_service.controller.CartController;
import com.msb.ecom.order_service.dto.CartResponse;
import com.msb.ecom.order_service.dto.CartValidationResponse;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.service.CartService;
import com.msb.ecom.order_service.service.CartValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CartController.class)
@Import({SecurityConfig.class, CartControllerTests.CartTestConfiguration.class})
class CartControllerTests {

    private static final String LISTING_ID = "01L00000000000000000000001";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeCartService cartService;

    @Autowired
    FakeCartValidationService cartValidationService;

    @Test
    void cartRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedBuyerCanReadCart() throws Exception {
        cartService.response = new CartResponse(
                0,
                null,
                0,
                0,
                List.of(),
                List.of());

        mockMvc.perform(get("/api/v1/cart").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", equalTo(0)))
                .andExpect(jsonPath("$.itemCount", equalTo(0)));
    }

    @Test
    void addValidatesListingIdAndPositiveQuantity() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"listingId":"short","quantity":0}
                                """))
                .andExpect(status().isBadRequest());

        cartService.response = new CartResponse(
                1,
                null,
                1,
                1,
                List.of(),
                List.of());
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(jwt())
                        .header("If-Match", "\"0\"")
                        .header("Idempotency-Key", "cart-add-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"listingId":"%s","quantity":1}
                                """.formatted(LISTING_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount", equalTo(1)));
    }

    @Test
    void cartMutationsRequireVersionAndIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/cart/items")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"listingId":"%s","quantity":1}
                                """.formatted(LISTING_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("CART_VERSION_REQUIRED")));

        mockMvc.perform(post("/api/v1/cart/items")
                        .with(jwt())
                        .header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"listingId":"%s","quantity":1}
                                """.formatted(LISTING_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("CART_IDEMPOTENCY_KEY_REQUIRED")));
    }

    @Test
    void authenticatedBuyerCanUpdateRemoveAndClearCart() throws Exception {
        cartService.response = new CartResponse(
                2,
                null,
                1,
                2,
                List.of(),
                List.of());

        mockMvc.perform(patch("/api/v1/cart/items/{listingId}", LISTING_ID)
                        .with(jwt())
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "cart-update-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuantity", equalTo(2)));

        cartService.response = new CartResponse(
                3,
                null,
                0,
                0,
                List.of(),
                List.of());
        mockMvc.perform(delete("/api/v1/cart/items/{listingId}", LISTING_ID)
                        .with(jwt())
                        .header("If-Match", "\"2\"")
                        .header("Idempotency-Key", "cart-remove-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount", equalTo(0)));

        mockMvc.perform(delete("/api/v1/cart")
                        .with(jwt())
                        .header("If-Match", "3")
                        .header("Idempotency-Key", "cart-clear-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQuantity", equalTo(0)));
    }

    @Test
    void authenticatedBuyerCanValidateCart() throws Exception {
        cartValidationService.response = new CartValidationResponse(
                3,
                Instant.parse("2026-07-18T12:00:00Z"),
                true,
                1,
                2,
                List.of(),
                List.of(),
                List.of());

        mockMvc.perform(post("/api/v1/cart/validate").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartVersion", equalTo(3)))
                .andExpect(jsonPath("$.checkoutReady", equalTo(true)));
    }

    @TestConfiguration
    static class CartTestConfiguration {

        @Bean
        FakeCartService cartService() {
            return new FakeCartService();
        }

        @Bean
        FakeCartValidationService cartValidationService() {
            return new FakeCartValidationService();
        }
    }

    static final class FakeCartService extends CartService {

        private CartResponse response;

        FakeCartService() {
            super(null, null, null, null, 999);
        }

        @Override
        public CartResponse get() {
            return response;
        }

        @Override
        public CartResponse add(String listingId, int quantity, String ifMatch, String idempotencyKey) {
            requireMutationHeaders(ifMatch, idempotencyKey);
            return response;
        }

        @Override
        public CartResponse update(String listingId, int quantity, String ifMatch, String idempotencyKey) {
            requireMutationHeaders(ifMatch, idempotencyKey);
            return response;
        }

        @Override
        public CartResponse remove(String listingId, String ifMatch, String idempotencyKey) {
            requireMutationHeaders(ifMatch, idempotencyKey);
            return response;
        }

        @Override
        public CartResponse clear(String ifMatch, String idempotencyKey) {
            requireMutationHeaders(ifMatch, idempotencyKey);
            return response;
        }

        private void requireMutationHeaders(String ifMatch, String idempotencyKey) {
            if (ifMatch == null || ifMatch.isBlank()) {
                throw new CartException(
                        HttpStatus.BAD_REQUEST,
                        "CART_VERSION_REQUIRED",
                        "If-Match must contain the current cart version.");
            }
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new CartException(
                        HttpStatus.BAD_REQUEST,
                        "CART_IDEMPOTENCY_KEY_REQUIRED",
                        "A valid Idempotency-Key is required.");
            }
        }
    }

    static final class FakeCartValidationService extends CartValidationService {

        private CartValidationResponse response;

        FakeCartValidationService() {
            super(null, null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public CartValidationResponse validate() {
            return response;
        }
    }
}
