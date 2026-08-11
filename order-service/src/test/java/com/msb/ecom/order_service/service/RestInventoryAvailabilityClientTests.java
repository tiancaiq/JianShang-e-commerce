package com.msb.ecom.order_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestInventoryAvailabilityClientTests {

    private RestInventoryAvailabilityClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestInventoryAvailabilityClient(
                builder,
                "http://inventory.test",
                "local-commerce-service-token");
    }

    @Test
    void acceptsAdditiveInventoryAvailabilityFields() {
        server.expect(once(), requestTo(
                        "http://inventory.test/api/v1/internal/inventory/01KXQMEH9KBPH5S7DPBFM0VBJ5/availability"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Service-Token", "local-commerce-service-token"))
                .andRespond(withSuccess("""
                        {
                          "listingId": "01KXQMEH9KBPH5S7DPBFM0VBJ5",
                          "businessId": "01KXQBUSI00000000000000001",
                          "initialized": true,
                          "onHand": 3,
                          "reserved": 1,
                          "available": 2,
                          "version": 4
                        }
                        """, MediaType.APPLICATION_JSON));

        InventoryAvailabilityClient.Availability availability =
                client.get("01KXQMEH9KBPH5S7DPBFM0VBJ5");

        assertThat(availability.initialized()).isTrue();
        assertThat(availability.businessId()).isEqualTo("01KXQBUSI00000000000000001");
        assertThat(availability.available()).isEqualTo(2);
        assertThat(availability.version()).isEqualTo(4);
        server.verify();
    }
}
