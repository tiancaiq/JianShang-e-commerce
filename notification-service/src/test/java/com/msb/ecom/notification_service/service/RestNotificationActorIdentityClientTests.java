package com.msb.ecom.notification_service.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.msb.ecom.notification_service.service.NotificationCursorCodecTests.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestNotificationActorIdentityClientTests {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestNotificationActorIdentityClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://auth.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestNotificationActorIdentityClient(builder.build());
    }

    @Test
    void relaysOnlyOriginalBearerAndCorrelationAndReturnsActiveInternalId() {
        server.expect(once(), requestTo("http://auth.test/api/v1/users/me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer opaque-token"))
                .andExpect(header("X-Correlation-Id", "notification-correlation-1"))
                .andRespond(withSuccess(
                        "{\"data\":{\"id\":\"" + id(7) + "\",\"status\":\"ACTIVE\","
                                + "\"email\":\"ignored@example.invalid\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.requireActiveUserId(
                "Bearer opaque-token", "notification-correlation-1")).isEqualTo(id(7));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401})
    void authenticationFailureStaysAuthenticationFailure(int status) {
        expectStatus(status);
        assertThatThrownBy(() -> client.requireActiveUserId("Bearer token", "c"))
                .isInstanceOf(NotificationActorIdentityClient.AuthenticationRequiredException.class);
    }

    @Test
    void inactiveActorIsDenied() {
        server.expect(requestTo("http://auth.test/api/v1/users/me"))
                .andRespond(withSuccess(
                        "{\"data\":{\"id\":\"" + id(7) + "\",\"status\":\"SUSPENDED\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.requireActiveUserId("Bearer token", "c"))
                .isInstanceOf(NotificationActorIdentityClient.AccessDeniedException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500, 503})
    void dependencyAndMalformedResponsesStayUnavailable(int status) {
        expectStatus(status);
        assertThatThrownBy(() -> client.requireActiveUserId("Bearer token", "c"))
                .isInstanceOf(NotificationActorIdentityClient.DependencyUnavailableException.class);
    }

    @Test
    void malformedSuccessIsUnavailable() {
        server.expect(requestTo("http://auth.test/api/v1/users/me"))
                .andRespond(withSuccess(
                        "{\"data\":{\"id\":\"client-supplied\",\"status\":\"ACTIVE\"}}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.requireActiveUserId("Bearer token", "c"))
                .isInstanceOf(NotificationActorIdentityClient.DependencyUnavailableException.class);
    }

    private void expectStatus(int status) {
        server.expect(requestTo("http://auth.test/api/v1/users/me"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)));
    }
}
