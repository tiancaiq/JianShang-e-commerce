package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.OrderConfirmationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestInventoryReservationClientTests {

    private static final String CHECKOUT_ID = "01K00000000000000000000001";
    private static final String RESERVATION_ID = "01K00000000000000000000700";

    @Test
    void commitForwardsDeterministicIdempotencyAndCorrelationHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestInventoryReservationClient client =
                new RestInventoryReservationClient(builder, "http://inventory.test", "test-token");
        server.expect(once(), requestTo(
                        "http://inventory.test/api/v1/internal/inventory/reservations/"
                                + RESERVATION_ID + "/commit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "test-token"))
                .andExpect(header("Idempotency-Key", "order-confirm:" + CHECKOUT_ID))
                .andExpect(header("X-Correlation-Id", "correlation-order-commit"))
                .andRespond(withSuccess("""
                        {
                          "id":"01K00000000000000000000700",
                          "checkoutId":"01K00000000000000000000001",
                          "purpose":"CHECKOUT",
                          "status":"COMMITTED",
                          "usable":false,
                          "expiresAt":"2026-07-20T01:15:00Z",
                          "version":2,
                          "items":[
                            {"listingId":"01K00000000000000000000300","quantity":1}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        InventoryReservationClient.Reservation result =
                client.commit(CHECKOUT_ID, RESERVATION_ID, "correlation-order-commit");

        assertThat(result.status()).isEqualTo("COMMITTED");
        assertThat(result.checkoutId()).isEqualTo(CHECKOUT_ID);
        server.verify();
    }

    @Test
    void expiredReservationMapsToExplicitRecoveryWithoutLeakingResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestInventoryReservationClient client =
                new RestInventoryReservationClient(builder, "http://inventory.test", "test-token");
        server.expect(requestTo(
                        "http://inventory.test/api/v1/internal/inventory/reservations/"
                                + RESERVATION_ID + "/commit"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("""
                        {"error":{"code":"INVENTORY_RESERVATION_EXPIRED","message":"internal"}}
                        """).contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                client.commit(CHECKOUT_ID, RESERVATION_ID, "correlation-order-commit"))
                .isInstanceOfSatisfying(OrderConfirmationException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(
                            "ORDER_CONFIRMATION_REQUIRES_RECOVERY");
                    assertThat(exception.getMessage()).doesNotContain("internal");
                });
        server.verify();
    }
}
