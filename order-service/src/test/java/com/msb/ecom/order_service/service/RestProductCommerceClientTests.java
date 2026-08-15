package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.CheckoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class RestProductCommerceClientTests {

    private RestProductCommerceClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://product.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestProductCommerceClient(
                builder,
                "http://product.test",
                "local-commerce-service-token");
    }

    @Test
    void acceptsAdditiveProductCommerceFields() {
        server.expect(once(), requestTo(
                        "http://product.test/api/v1/internal/store/items/01KXQMEH9KBPH5S7DPBFM0VBJ5/commerce-context"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", "local-commerce-service-token"))
                .andRespond(withSuccess("""
                        {
                          "listingId": "01KXQMEH9KBPH5S7DPBFM0VBJ5",
                          "businessId": "01KXQBUSI00000000000000001",
                          "storeId": "01KXQBUSI00000000000000001",
                          "storeName": "Shen Ban Demo Store",
                          "storeSlug": "shen-ban-demo-store",
                          "businessVerified": true,
                          "publicCity": "Irvine",
                          "publicRegion": "CA",
                          "sellerType": "BUSINESS",
                          "title": "1",
                          "sku": "221",
                          "condition": "GOOD",
                          "priceAmount": 1.0000,
                          "currency": "USD",
                          "quantity": 1,
                          "status": "ACTIVE",
                          "publicationSource": "BUSINESS_SELF_PUBLISHED",
                          "version": 1,
                          "thumbnailUrl": "/api/v1/public/listing-media/01KXQMEMK0RGGAAC781X5XWNSJ"
                        }
                        """, MediaType.APPLICATION_JSON));

        ProductCommerceClient.ProductContext context =
                client.find("01KXQMEH9KBPH5S7DPBFM0VBJ5").orElseThrow();

        assertThat(context.sellerType()).isEqualTo("BUSINESS");
        assertThat(context.priceAmount()).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(context.storeName()).isEqualTo("Shen Ban Demo Store");
        assertThat(context.storeSlug()).isEqualTo("shen-ban-demo-store");
        assertThat(context.businessVerified()).isTrue();
        server.verify();
    }

    @Test
    void rejectsKnownPurchasabilityRestrictionWithStableForbiddenCode() {
        server.expect(once(), requestTo(
                        "http://product.test/api/v1/internal/listings/capabilities/evaluate-batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "local-commerce-service-token"))
                .andRespond(withSuccess("""
                        {"evaluatedAt":"2026-08-15T10:30:00Z",
                         "listings":[{"listingId":"01KXQMEH9KBPH5S7DPBFM0VBJ5","decisions":[
                          {"scope":"LISTING_PURCHASABILITY","allowed":false,
                           "effectiveAction":"SUSPEND",
                           "enforcementActionId":"01KZZZZZZZZZZZZZZZZZZZZZZZ",
                           "expiresAt":"2026-08-16T10:30:00Z",
                           "supportReference":"LISTING-M0VBJ5"}
                        ]}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.requirePurchasable(Set.of("01KXQMEH9KBPH5S7DPBFM0VBJ5")))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(403);
                    assertThat(exception.code()).isEqualTo("LISTING_PURCHASABILITY_RESTRICTED");
                });
        server.verify();
    }

    @Test
    void failsClosedWhenProductDecisionIsUnavailable() {
        server.expect(once(), requestTo(
                        "http://product.test/api/v1/internal/listings/capabilities/evaluate-batch"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.requirePurchasable(Set.of("01KXQMEH9KBPH5S7DPBFM0VBJ5")))
                .isInstanceOfSatisfying(CheckoutException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("LISTING_ENFORCEMENT_DECISION_UNAVAILABLE");
                });
        server.verify();
    }
}
