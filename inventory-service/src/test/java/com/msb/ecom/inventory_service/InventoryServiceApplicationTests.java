package com.msb.ecom.inventory_service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.inventory_service.model.InventoryException;
import com.msb.ecom.inventory_service.service.InventoryAuthorizationClient;
import com.msb.ecom.inventory_service.service.InventoryReservationService;
import com.msb.ecom.inventory_service.service.ProductCommerceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "commerce.internal-service-token=test-commerce-token",
        "inventory.reservations.expiry-enabled=false",
        "inventory.cancellation-restock.enabled=true",
        "inventory.return-restock-enabled=true"
})
@AutoConfigureMockMvc
class InventoryServiceApplicationTests {

    private static final String USER_ID = "01U00000000000000000000001";
    private static final String BUSINESS_ID = "01B00000000000000000000001";
    private static final String OTHER_BUSINESS_ID = "01B00000000000000000000002";
    private static final String LISTING_ID = "01L00000000000000000000001";
    private static final String SECOND_LISTING_ID = "01L00000000000000000000002";

    @ServiceConnection
    static MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("inventory")
            .withUsername("inventory")
            .withPassword("inventory");

    static {
        mysqlContainer.start();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    InventoryReservationService reservationService;

    @MockBean
    InventoryAuthorizationClient authorizationClient;

    @MockBean
    ProductCommerceClient productCommerceClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from inventory_return_restocks");
        jdbcTemplate.update("delete from inventory_cancellation_restocks");
        jdbcTemplate.update("delete from inventory_reservation_history");
        jdbcTemplate.update("delete from inventory_reservation_items");
        jdbcTemplate.update("delete from inventory_reservations");
        jdbcTemplate.update("delete from inventory_outbox_events");
        jdbcTemplate.update("delete from inventory_idempotency_records");
        jdbcTemplate.update("delete from inventory_movements");
        jdbcTemplate.update("delete from inventory_items");

        when(authorizationClient.requirePermission(anyString(), eq(BUSINESS_ID), anyString()))
                .thenReturn(new InventoryAuthorizationClient.BusinessAuthorization(
                        BUSINESS_ID, USER_ID, "OWNER"));
        when(authorizationClient.requirePermission(anyString(), eq(OTHER_BUSINESS_ID), anyString()))
                .thenThrow(new InventoryException(
                        HttpStatus.FORBIDDEN,
                        "INVENTORY_FORBIDDEN",
                        "Inventory access is not allowed for this business."));
        when(productCommerceClient.getBusinessItem(BUSINESS_ID, LISTING_ID))
                .thenReturn(catalogItem(LISTING_ID, BUSINESS_ID, "ACTIVE", 4, 2));
        when(productCommerceClient.getBusinessItem(BUSINESS_ID, SECOND_LISTING_ID))
                .thenReturn(catalogItem(SECOND_LISTING_ID, BUSINESS_ID, "ACTIVE", 4, 2));
        when(productCommerceClient.getBusinessItems(
                eq(BUSINESS_ID), nullable(String.class), nullable(String.class), nullable(String.class), eq(24)))
                .thenReturn(new ProductCommerceClient.CatalogPage(
                        List.of(catalogItem(LISTING_ID, BUSINESS_ID, "ACTIVE", 4, 2)),
                        new ProductCommerceClient.PageMetadata(null, false)));
    }

    @Test
    void inventoryEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/{businessId}/inventory", BUSINESS_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalAvailabilityUsesServiceAuthenticationWithoutBuyerInventoryPermission() throws Exception {
        initialize("initialize-internal-availability", "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/internal/inventory/{listingId}/availability", LISTING_ID)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.businessId", equalTo(BUSINESS_ID)))
                .andExpect(jsonPath("$.initialized", equalTo(true)))
                .andExpect(jsonPath("$.available", equalTo(8)));

        mockMvc.perform(get("/api/v1/internal/inventory/{listingId}/availability", LISTING_ID)
                        .header("X-Internal-Service-Token", "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_INTERNAL_AUTH_REQUIRED")));
    }

    @Test
    void catalogPageComposesListingStateBeforeInventoryInitialization() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/{businessId}/inventory", BUSINESS_ID)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].listingId", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.data[0].catalogQuantitySuggestion", equalTo(4)))
                .andExpect(jsonPath("$.data[0].inventoryState", equalTo("NOT_INITIALIZED")))
                .andExpect(jsonPath("$.data[0].inventory").doesNotExist());
    }

    @Test
    void initializationIsIdempotentAndWritesMovementAndOutboxOnce() throws Exception {
        String body = """
                {"onHand":8,"note":"Opening count"}
                """;

        initialize("initialize-1", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id", notNullValue()))
                .andExpect(jsonPath("$.data.onHand", equalTo(8)))
                .andExpect(jsonPath("$.data.available", equalTo(8)))
                .andExpect(jsonPath("$.data.version", equalTo(0)));
        initialize("initialize-1", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.onHand", equalTo(8)));

        assertCount("inventory_items", 1);
        assertCount("inventory_movements", 1);
        assertCount("inventory_outbox_events", 1);
        assertCount("inventory_idempotency_records", 1);
    }

    @Test
    void adjustmentUsesOptimisticVersionAndPreservesAppendOnlyLedger() throws Exception {
        initialize("initialize-2", "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/businesses/{businessId}/inventory/{listingId}/adjustments",
                        BUSINESS_ID, LISTING_ID)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token")))
                        .header("If-Match", "0")
                        .header("Idempotency-Key", "adjust-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operation":"ADJUST",
                                  "quantity":-3,
                                  "reason":"STOCK_COUNT_CORRECTION",
                                  "note":"Cycle count"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onHand", equalTo(5)))
                .andExpect(jsonPath("$.data.version", equalTo(1)));

        mockMvc.perform(get("/api/v1/businesses/{businessId}/inventory/{listingId}/movements",
                        BUSINESS_ID, LISTING_ID)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", equalTo(2)))
                .andExpect(jsonPath("$.data[0].quantityDelta", equalTo(-3)))
                .andExpect(jsonPath("$.data[0].onHandBefore", equalTo(8)))
                .andExpect(jsonPath("$.data[0].onHandAfter", equalTo(5)));

        assertCount("inventory_movements", 2);
        assertCount("inventory_outbox_events", 2);
    }

    @Test
    void staleVersionAndNegativeBalanceAreRejected() throws Exception {
        initialize("initialize-3", "{\"onHand\":2,\"note\":null}")
                .andExpect(status().isCreated());

        adjust("adjust-valid", "0", 1)
                .andExpect(status().isOk());
        adjust("adjust-stale", "0", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_VERSION_CONFLICT")));
        adjust("adjust-negative", "1", -4)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_INVALID_ADJUSTMENT")));

        assertCount("inventory_movements", 2);
    }

    @Test
    void crossBusinessAccessIsRejectedWithoutLeakingInventory() throws Exception {
        mockMvc.perform(get("/api/v1/businesses/{businessId}/inventory", OTHER_BUSINESS_ID)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_FORBIDDEN")));

        assertCount("inventory_items", 0);
    }

    @Test
    void reservationRequiresInternalServiceAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/internal/inventory/reservations")
                        .header("Idempotency-Key", "reserve-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reservationBody(
                                "01C00000000000000000000001",
                                Instant.now().plusSeconds(3600),
                                "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":1}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_INTERNAL_AUTH_REQUIRED")));
    }

    @Test
    void reservationAndReleaseAreIdempotentAndRestoreAvailability() throws Exception {
        initialize("initialize-reserve-release", "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        Instant expiresAt = Instant.now().plusSeconds(3600);
        String body = reservationBody(
                "01C00000000000000000000002",
                expiresAt,
                "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":3}");

        String reservationId = reservation("reserve-release", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", equalTo("ACTIVE")))
                .andExpect(jsonPath("$.items[0].reserved", equalTo(3)))
                .andExpect(jsonPath("$.items[0].available", equalTo(5)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        reservationId = objectMapper.readTree(reservationId).get("id").asText();

        reservation("reserve-release", body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", equalTo(reservationId)));

        release(reservationId, "release-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("RELEASED")))
                .andExpect(jsonPath("$.items[0].reserved", equalTo(0)))
                .andExpect(jsonPath("$.items[0].available", equalTo(8)));
        release(reservationId, "release-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("RELEASED")));

        assertBalance(LISTING_ID, 8, 0, 2);
        assertCount("inventory_reservations", 1);
        assertCount("inventory_reservation_history", 2);
        assertEventCount("inventory.reservation.released.v1", 1);
    }

    @Test
    void commitReducesOnHandAndReservedExactlyOnce() throws Exception {
        initialize("initialize-commit", "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        String body = reservationBody(
                "01C00000000000000000000003",
                Instant.now().plusSeconds(3600),
                "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":3}");
        String reservationId = responseId(reservation("reserve-commit", body).andReturn());

        commit(reservationId, "commit-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("COMMITTED")))
                .andExpect(jsonPath("$.items[0].onHand", equalTo(5)))
                .andExpect(jsonPath("$.items[0].reserved", equalTo(0)))
                .andExpect(jsonPath("$.items[0].available", equalTo(5)));
        commit(reservationId, "commit-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("COMMITTED")));

        assertBalance(LISTING_ID, 5, 0, 2);
        assertEventCount("inventory.reservation.committed.v1", 1);
        assertCount("inventory_reservation_history", 2);
    }

    @Test
    void cancellationRestockRestoresCommittedMultiLineQuantitiesExactlyOnce() throws Exception {
        initialize("initialize-cancel-1", LISTING_ID, "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        initialize("initialize-cancel-2", SECOND_LISTING_ID, "{\"onHand\":4,\"note\":null}")
                .andExpect(status().isCreated());
        String items = """
                {"listingId":"%s","quantity":3},
                {"listingId":"%s","quantity":2}
                """.formatted(LISTING_ID, SECOND_LISTING_ID);
        String reservationId = responseId(reservation(
                "reserve-cancel", reservationBody(
                        "01C00000000000000000000020", Instant.now().plusSeconds(3600), items))
                .andExpect(status().isCreated()).andReturn());
        commit(reservationId, "commit-cancel").andExpect(status().isOk());
        assertBalance(LISTING_ID, 5, 0, 2);
        assertBalance(SECOND_LISTING_ID, 2, 0, 2);

        String body = """
                {"orderId":"01K00000000000000000000020",
                 "cancellationRequestId":"01K00000000000000000000021"}
                """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                            "/api/v1/internal/inventory/reservations/{id}/cancellation-restocks",
                            reservationId)
                            .header("X-Internal-Service-Token", "test-commerce-token")
                            .header("Idempotency-Key", "cancel-restock-key-20")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", equalTo("COMPLETED")))
                    .andExpect(jsonPath("$.restoredQuantity", equalTo(5)));
        }

        assertBalance(LISTING_ID, 8, 0, 3);
        assertBalance(SECOND_LISTING_ID, 4, 0, 3);
        assertCount("inventory_cancellation_restocks", 1);
        Integer movements = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inventory_movements
                WHERE reason_code = 'ORDER_CANCELLATION_RESTOCK'
                  AND note LIKE '%01K00000000000000000000020%'
                """, Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(2, movements);
    }

    @Test
    void customerReturnRestockRestoresOnlyTheCommandedBusinessGroupExactlyOnce() throws Exception {
        initialize("initialize-return-1", LISTING_ID, "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        initialize("initialize-return-2", SECOND_LISTING_ID, "{\"onHand\":4,\"note\":null}")
                .andExpect(status().isCreated());
        String items = """
                {"listingId":"%s","quantity":3},
                {"listingId":"%s","quantity":2}
                """.formatted(LISTING_ID, SECOND_LISTING_ID);
        String reservationId = responseId(reservation(
                "reserve-return", reservationBody(
                        "01C00000000000000000000030", Instant.now().plusSeconds(3600), items))
                .andExpect(status().isCreated()).andReturn());
        commit(reservationId, "commit-return").andExpect(status().isOk());

        String body = """
                {"returnId":"01K00000000000000000000031",
                 "orderId":"01K00000000000000000000032",
                 "businessId":"%s"}
                """.formatted(BUSINESS_ID);
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/internal/inventory/reservations/{id}/return-restocks",
                            reservationId)
                            .header("X-Internal-Service-Token", "test-commerce-token")
                            .header("Idempotency-Key", "return-restock-key-30")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status", equalTo("COMPLETED")))
                    .andExpect(jsonPath("$.restoredQuantity", equalTo(5)));
        }

        assertBalance(LISTING_ID, 8, 0, 3);
        assertBalance(SECOND_LISTING_ID, 4, 0, 3);
        assertCount("inventory_return_restocks", 1);
        Integer movements = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM inventory_movements
                WHERE reason_code = 'CUSTOMER_RETURN_RESTOCK'
                  AND actor_user_id = '01K00000000000000000000031'
                """, Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(2, movements);
    }

    @Test
    void commitAfterReleaseFailsAndSignalsRecoveryOnce() throws Exception {
        initialize("initialize-commit-rejected", "{\"onHand\":5,\"note\":null}")
                .andExpect(status().isCreated());
        String body = reservationBody(
                "01C00000000000000000000004",
                Instant.now().plusSeconds(3600),
                "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":2}");
        String reservationId = responseId(reservation("reserve-rejected", body).andReturn());
        release(reservationId, "release-before-commit").andExpect(status().isOk());

        commit(reservationId, "commit-rejected-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_RESERVATION_NOT_COMMITTABLE")));
        commit(reservationId, "commit-rejected-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_RESERVATION_NOT_COMMITTABLE")));

        assertBalance(LISTING_ID, 5, 0, 2);
        assertEventCount("inventory.reservation.commit-rejected.v1", 1);
        Integer rejected = jdbcTemplate.queryForObject("""
                select count(*) from inventory_reservation_history
                where event_type = 'COMMIT_REJECTED'
                """, Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, rejected);
    }

    @Test
    void expiryWorkerRestoresAvailabilityOnce() throws Exception {
        initialize("initialize-expiry", "{\"onHand\":6,\"note\":null}")
                .andExpect(status().isCreated());
        String body = reservationBody(
                "01C00000000000000000000005",
                Instant.now().plusSeconds(3600),
                "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":4}");
        String reservationId = responseId(reservation("reserve-expiry", body).andReturn());
        jdbcTemplate.update(
                "update inventory_reservations set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                reservationId);

        org.junit.jupiter.api.Assertions.assertEquals(1, reservationService.expireDueReservations());
        org.junit.jupiter.api.Assertions.assertEquals(0, reservationService.expireDueReservations());

        mockMvc.perform(get("/api/v1/internal/inventory/reservations/{id}", reservationId)
                        .header("X-Internal-Service-Token", "test-commerce-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("EXPIRED")))
                .andExpect(jsonPath("$.usable", equalTo(false)));
        assertBalance(LISTING_ID, 6, 0, 2);
        assertEventCount("inventory.reservation.expired.v1", 1);
    }

    @Test
    void multiLineInsufficientStockRollsBackEveryBalance() throws Exception {
        initialize("initialize-multi-1", LISTING_ID, "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        initialize("initialize-multi-2", SECOND_LISTING_ID, "{\"onHand\":4,\"note\":null}")
                .andExpect(status().isCreated());
        String items = """
                {"listingId":"%s","quantity":2},
                {"listingId":"%s","quantity":5}
                """.formatted(LISTING_ID, SECOND_LISTING_ID);

        reservation(
                "reserve-multi-fail",
                reservationBody("01C00000000000000000000006", Instant.now().plusSeconds(3600), items))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_INSUFFICIENT_STOCK")));

        assertBalance(LISTING_ID, 8, 0, 0);
        assertBalance(SECOND_LISTING_ID, 4, 0, 0);
        assertCount("inventory_reservations", 0);
        assertCount("inventory_reservation_items", 0);
    }

    @Test
    void multiLineReservationAndReleaseUpdateEveryItemTogether() throws Exception {
        initialize("initialize-multi-success-1", LISTING_ID, "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        initialize("initialize-multi-success-2", SECOND_LISTING_ID, "{\"onHand\":4,\"note\":null}")
                .andExpect(status().isCreated());
        String items = """
                {"listingId":"%s","quantity":2},
                {"listingId":"%s","quantity":3}
                """.formatted(SECOND_LISTING_ID, LISTING_ID);
        String reservationId = responseId(reservation(
                "reserve-multi-success",
                reservationBody("01C00000000000000000000009", Instant.now().plusSeconds(3600), items))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()", equalTo(2)))
                .andReturn());

        assertBalance(LISTING_ID, 8, 3, 1);
        assertBalance(SECOND_LISTING_ID, 4, 2, 1);
        release(reservationId, "release-multi-success")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", equalTo("RELEASED")));
        assertBalance(LISTING_ID, 8, 0, 2);
        assertBalance(SECOND_LISTING_ID, 4, 0, 2);
        assertCount("inventory_reservation_items", 2);
    }

    @Test
    void reservationIdempotencyKeyCannotBeReusedWithDifferentItems() throws Exception {
        initialize("initialize-idempotency-conflict", "{\"onHand\":8,\"note\":null}")
                .andExpect(status().isCreated());
        Instant expiresAt = Instant.now().plusSeconds(3600);
        reservation(
                "reserve-key-conflict",
                reservationBody(
                        "01C00000000000000000000010",
                        expiresAt,
                        "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":2}"))
                .andExpect(status().isCreated());
        reservation(
                "reserve-key-conflict",
                reservationBody(
                        "01C00000000000000000000010",
                        expiresAt,
                        "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code", equalTo("IDEMPOTENCY_KEY_REUSED")));

        assertBalance(LISTING_ID, 8, 2, 1);
        assertCount("inventory_reservations", 1);
    }

    @Test
    void sellerAdjustmentCannotReduceOnHandBelowActiveReservation() throws Exception {
        initialize("initialize-adjust-reserved", "{\"onHand\":5,\"note\":null}")
                .andExpect(status().isCreated());
        reservation(
                "reserve-before-adjust",
                reservationBody(
                        "01C00000000000000000000011",
                        Instant.now().plusSeconds(3600),
                        "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":4}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/businesses/{businessId}/inventory/{listingId}/adjustments",
                        BUSINESS_ID, LISTING_ID)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token")))
                        .header("If-Match", "1")
                        .header("Idempotency-Key", "adjust-below-reserved")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operation":"SET",
                                  "quantity":3,
                                  "reason":"STOCK_COUNT_CORRECTION",
                                  "note":"Count cannot consume reserved stock"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code", equalTo("INVENTORY_AVAILABLE_WOULD_BE_NEGATIVE")));

        assertBalance(LISTING_ID, 5, 4, 1);
    }

    @Test
    void concurrentReservationsCannotOversell() throws Exception {
        initialize("initialize-concurrent", "{\"onHand\":5,\"note\":null}")
                .andExpect(status().isCreated());
        Instant expiresAt = Instant.now().plusSeconds(3600);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                    () -> concurrentReserve(
                            "reserve-race-1",
                            "01C00000000000000000000007",
                            expiresAt,
                            ready,
                            start),
                    executor);
            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                    () -> concurrentReserve(
                            "reserve-race-2",
                            "01C00000000000000000000008",
                            expiresAt,
                            ready,
                            start),
                    executor);
            ready.await();
            start.countDown();
            List<Integer> statuses = List.of(first.join(), second.join());
            org.junit.jupiter.api.Assertions.assertEquals(1, statuses.stream().filter(value -> value == 201).count());
            org.junit.jupiter.api.Assertions.assertEquals(1, statuses.stream().filter(value -> value == 409).count());
        }

        assertBalance(LISTING_ID, 5, 4, 1);
        assertCount("inventory_reservations", 1);
        assertCount("inventory_reservation_items", 1);
    }

    private org.springframework.test.web.servlet.ResultActions initialize(String key, String body) throws Exception {
        return initialize(key, LISTING_ID, body);
    }

    private org.springframework.test.web.servlet.ResultActions initialize(
            String key,
            String listingId,
            String body) throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/{businessId}/inventory/{listingId}/initialize",
                        BUSINESS_ID, listingId)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token")))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions adjust(String key, String version, int quantity)
            throws Exception {
        return mockMvc.perform(post("/api/v1/businesses/{businessId}/inventory/{listingId}/adjustments",
                        BUSINESS_ID, LISTING_ID)
                        .with(jwt().jwt(token -> token.tokenValue("seller-token")))
                        .header("If-Match", version)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operation":"ADJUST",
                                  "quantity":%d,
                                  "reason":"STOCK_COUNT_CORRECTION",
                                  "note":null
                                }
                                """.formatted(quantity)));
    }

    private org.springframework.test.web.servlet.ResultActions reservation(String key, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/internal/inventory/reservations")
                .header("X-Internal-Service-Token", "test-commerce-token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions release(String reservationId, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/internal/inventory/reservations/{id}/release", reservationId)
                .header("X-Internal-Service-Token", "test-commerce-token")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"CHECKOUT_CANCELLED\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions commit(String reservationId, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/internal/inventory/reservations/{id}/commit", reservationId)
                .header("X-Internal-Service-Token", "test-commerce-token")
                .header("Idempotency-Key", key));
    }

    private String reservationBody(
            String checkoutId,
            Instant expiresAt,
            String itemJson) {
        return """
                {
                  "checkoutId":"%s",
                  "purpose":"CHECKOUT",
                  "expiresAt":"%s",
                  "items":[%s]
                }
                """.formatted(checkoutId, expiresAt, itemJson);
    }

    private String responseId(org.springframework.test.web.servlet.MvcResult result) {
        try {
            JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
            return response.get("id").asText();
        } catch (Exception exception) {
            throw new AssertionError("Reservation response could not be read.", exception);
        }
    }

    private int concurrentReserve(
            String key,
            String checkoutId,
            Instant expiresAt,
            CountDownLatch ready,
            CountDownLatch start) {
        try {
            ready.countDown();
            start.await();
            return reservation(
                    key,
                    reservationBody(
                            checkoutId,
                            expiresAt,
                            "{\"listingId\":\"" + LISTING_ID + "\",\"quantity\":4}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private ProductCommerceClient.CatalogItem catalogItem(
            String listingId,
            String businessId,
            String status,
            int quantity,
            long version) {
        return new ProductCommerceClient.CatalogItem(
                listingId,
                businessId,
                "01S00000000000000000000001",
                "BUSINESS",
                "Business keyboard",
                "SKU-1",
                new BigDecimal("25.75"),
                "USD",
                quantity,
                status,
                "BUSINESS_SELF_PUBLISHED",
                version);
    }

    private void assertCount(String table, int expected) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(expected, count);
    }

    private void assertBalance(String listingId, int onHand, int reserved, long version) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select on_hand, reserved, version
                from inventory_items
                where listing_id = ?
                """, listingId);
        org.junit.jupiter.api.Assertions.assertEquals(onHand, ((Number) row.get("on_hand")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(reserved, ((Number) row.get("reserved")).intValue());
        org.junit.jupiter.api.Assertions.assertEquals(version, ((Number) row.get("version")).longValue());
    }

    private void assertEventCount(String eventType, int expected) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from inventory_outbox_events
                where event_type = ?
                """, Integer.class, eventType);
        org.junit.jupiter.api.Assertions.assertEquals(expected, count);
    }
}
