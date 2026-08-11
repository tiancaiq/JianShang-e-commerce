package com.msb.ecom.order_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.order_service.model.CheckoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestPaymentIntentClientTests {

    private RestPaymentIntentClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payment.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPaymentIntentClient(
                builder.build(),
                "payment-test-token",
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void postsTheInternalContractWithServiceAuthOriginalKeyAndCorrelation() {
        PaymentIntentClient.Command command = command();
        server.expect(once(), requestTo("http://payment.test/api/v1/internal/payment-intents"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "payment-test-token"))
                .andExpect(header("Idempotency-Key", "payment-key-001"))
                .andExpect(header("X-Correlation-Id", "correlation-payment"))
                .andExpect(jsonPath("$.checkoutId").value(command.checkoutId()))
                .andExpect(jsonPath("$.checkoutVersion").value(4))
                .andExpect(jsonPath("$.checkoutSnapshotHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.buyerId").value(command.buyerId()))
                .andExpect(jsonPath("$.businessIds[0]").value(command.businessIds().get(0)))
                .andExpect(jsonPath("$.amount").value(30.0))
                .andRespond(withSuccess("""
                        {
                          "id":"01P00000000000000000000001",
                          "checkoutId":"01C00000000000000000000001",
                          "checkoutVersion":4,
                          "buyerId":"01U00000000000000000000001",
                          "businessIds":[
                            "01B00000000000000000000001",
                            "01B00000000000000000000002"
                          ],
                          "amount":30.0000,
                          "currency":"USD",
                          "provider":"FAKE_LOCAL_DEMO_V1",
                          "providerReference":"internal-reference",
                          "status":"REQUIRES_ACTION",
                          "version":1,
                          "expiresAt":"2026-07-20T01:15:00Z",
                          "providerAction":{
                            "type":"FAKE_HOSTED_ACTION",
                            "reference":"fake-action-reference"
                          },
                          "error":null,
                          "createdAt":"2026-07-20T01:00:00Z",
                          "updatedAt":"2026-07-20T01:00:01Z"
                        }
                        """, MediaType.APPLICATION_JSON));

        PaymentIntentClient.PaymentIntent response =
                client.create("payment-key-001", "correlation-payment", command);

        assertThat(response.status()).isEqualTo("REQUIRES_ACTION");
        assertThat(response.businessIds()).containsExactlyElementsOf(command.businessIds());
        server.verify();
    }

    @Test
    void mapsProviderIdempotencyConflictWithoutRetrying() {
        server.expect(once(), requestTo("http://payment.test/api/v1/internal/payment-intents"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":"PAYMENT_IDEMPOTENCY_CONFLICT"}}
                                """));

        assertThatThrownBy(() ->
                client.create("payment-key-001", "correlation-payment", command()))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo("PAYMENT_IDEMPOTENCY_CONFLICT");
                });
        server.verify();
    }

    @Test
    void mapsDisabledAndOutageResponsesToSafeOrderErrorsWithoutRetrying() {
        server.expect(once(), requestTo("http://payment.test/api/v1/internal/payment-intents"))
                .andRespond(withResourceNotFound()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":"PAYMENT_INTENTS_DISABLED"}}
                                """));

        assertThatThrownBy(() ->
                client.create("payment-key-001", "correlation-payment", command()))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(404);
                    assertThat(exception.code()).isEqualTo("CHECKOUT_PAYMENT_NOT_AVAILABLE");
                });
        server.verify();

        RestClient.Builder outageBuilder = RestClient.builder().baseUrl("http://payment.test");
        MockRestServiceServer outageServer = MockRestServiceServer.bindTo(outageBuilder).build();
        RestPaymentIntentClient outageClient = new RestPaymentIntentClient(
                outageBuilder.build(),
                "payment-test-token",
                new ObjectMapper().findAndRegisterModules());
        outageServer.expect(once(), requestTo("http://payment.test/api/v1/internal/payment-intents"))
                .andRespond(withServerError());

        assertThatThrownBy(() ->
                outageClient.create("payment-key-001", "correlation-payment", command()))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("CHECKOUT_PAYMENT_UNAVAILABLE");
                });
        outageServer.verify();
    }

    private PaymentIntentClient.Command command() {
        return new PaymentIntentClient.Command(
                "01C00000000000000000000001",
                4,
                "a".repeat(64),
                "01U00000000000000000000001",
                List.of(
                        "01B00000000000000000000001",
                        "01B00000000000000000000002"),
                new BigDecimal("30.0000"),
                "USD",
                Instant.parse("2026-07-20T01:15:00Z"));
    }
}
