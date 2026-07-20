package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.model.BusinessOrderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestBusinessOrderAuthorizationClientTests {

    private static final String BUSINESS_ID = id(1);
    private RestBusinessOrderAuthorizationClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestBusinessOrderAuthorizationClient(builder.build());
    }

    @Test
    void forwardsActorBearerAndReturnsFinanceCapability() {
        server.expect(once(), requestTo(
                        "http://auth.test/api/v1/businesses/" + BUSINESS_ID + "/membership/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer actor-token"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "businessId": "%s",
                            "userId": "%s",
                            "role": "OWNER",
                            "status": "ACTIVE",
                            "permissions": ["ORDER_VIEW", "ORDER_FINANCE_VIEW"]
                          }
                        }
                        """.formatted(BUSINESS_ID, id(2)), MediaType.APPLICATION_JSON));

        BusinessOrderAuthorizationClient.Access access =
                client.authorize("actor-token", BUSINESS_ID);

        assertThat(access.businessId()).isEqualTo(BUSINESS_ID);
        assertThat(access.financeView()).isTrue();
        server.verify();
    }

    @Test
    void missingOrderPermissionAndCrossBusinessPayloadUseNotFoundContract() {
        server.expect(requestTo(
                        "http://auth.test/api/v1/businesses/" + BUSINESS_ID + "/membership/me"))
                .andRespond(withSuccess("""
                        {"data":{"businessId":"%s","userId":"%s","role":"MANAGER",
                        "status":"ACTIVE","permissions":["INVENTORY_VIEW"]}}
                        """.formatted(BUSINESS_ID, id(2)), MediaType.APPLICATION_JSON));

        assertCode(() -> client.authorize("token", BUSINESS_ID), 404);
        server.verify();

        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth.test");
        MockRestServiceServer secondServer = MockRestServiceServer.bindTo(builder).build();
        RestBusinessOrderAuthorizationClient second =
                new RestBusinessOrderAuthorizationClient(builder.build());
        secondServer.expect(requestTo(
                        "http://auth.test/api/v1/businesses/" + BUSINESS_ID + "/membership/me"))
                .andRespond(withSuccess("""
                        {"data":{"businessId":"%s","userId":"%s","role":"OWNER",
                        "status":"ACTIVE","permissions":["ORDER_VIEW"]}}
                        """.formatted(id(99), id(2)), MediaType.APPLICATION_JSON));

        assertCode(() -> second.authorize("token", BUSINESS_ID), 404);
        secondServer.verify();
    }

    @Test
    void authDenialIsNonEnumeratingAndOutageIsUnavailable() {
        server.expect(requestTo(
                        "http://auth.test/api/v1/businesses/" + BUSINESS_ID + "/membership/me"))
                .andRespond(withResourceNotFound());

        assertCode(() -> client.authorize("token", BUSINESS_ID), 404);
        server.verify();

        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth.test");
        MockRestServiceServer outageServer = MockRestServiceServer.bindTo(builder).build();
        RestBusinessOrderAuthorizationClient outage =
                new RestBusinessOrderAuthorizationClient(builder.build());
        outageServer.expect(requestTo(
                        "http://auth.test/api/v1/businesses/" + BUSINESS_ID + "/membership/me"))
                .andRespond(withServerError());

        assertCode(() -> outage.authorize("token", BUSINESS_ID), 503);
        outageServer.verify();
    }

    private void assertCode(Runnable operation, int status) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BusinessOrderException.class, exception ->
                        assertThat(exception.status().value()).isEqualTo(status));
    }

    private static String id(int value) {
        return "01" + String.format("%024d", value);
    }
}
