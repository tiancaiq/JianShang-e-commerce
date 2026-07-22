package com.msb.ecom.product_service.service;

import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestAuthServiceClientReportEligibilityTests {

    private static final String BUSINESS_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAB";
    private static final String TOKEN = "opaque-test-token";
    private static final String URL =
            "http://auth.test/api/v1/businesses/" + BUSINESS_ID + "/membership/me";
    private static final String CURRENT_USER_URL = "http://auth.test/api/v1/users/me";

    private MockRestServiceServer server;
    private RestAuthServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestAuthServiceClient(builder, "http://auth.test");
    }

    @Test
    void activeEditableMembershipIsAuthoritativeOwnerEvidence() {
        expect(withSuccess("""
                {"data":{"businessId":"%s","userId":"01USER0000000000000000001",
                "role":"OWNER","status":"ACTIVE","permissions":["LISTING_DRAFT_CREATE"]}}
                """.formatted(BUSINESS_ID), org.springframework.http.MediaType.APPLICATION_JSON));

        assertThat(client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isEqualTo(AuthServiceClient.BusinessListingPermissionDecision.EDITABLE);
        server.verify();
    }

    @Test
    void validNonEditableMembershipAndNotFoundAreAuthoritativeNegativeResults() {
        expect(withSuccess("""
                {"data":{"businessId":"%s","userId":"01USER0000000000000000001",
                "role":"STAFF","status":"ACTIVE","permissions":[]}}
                """.formatted(BUSINESS_ID), org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isEqualTo(AuthServiceClient.BusinessListingPermissionDecision.NOT_EDITABLE);
        server.verify();

        resetServer();
        expect(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isEqualTo(AuthServiceClient.BusinessListingPermissionDecision.NOT_EDITABLE);
        server.verify();
    }

    @Test
    void unauthenticatedOrExpiredBearerFailsClosed() {
        expect(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isInstanceOf(AuthServiceClient.AuthenticationException.class)
                .hasMessage("Authenticated user is required.");
        server.verify();
    }

    @Test
    void forbiddenMembershipLookupFailsClosedAndIsNotNonMembership() {
        expect(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isInstanceOf(ListingAuthorizationException.class)
                .hasMessage("Business report eligibility could not be authorized.");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503})
    void authServerFailuresFailClosed(int status) {
        expect(withStatus(HttpStatusCode.valueOf(status)));

        assertThatThrownBy(() -> client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isInstanceOf(AuthServiceClient.DependencyUnavailableException.class)
                .hasMessage("Auth service is unavailable.");
        server.verify();
    }

    @Test
    void timeoutFailsClosedWithoutExposingTransportDetails() {
        expect(withException(new SocketTimeoutException("upstream-secret-body")));

        assertThatThrownBy(() -> client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isInstanceOf(AuthServiceClient.DependencyUnavailableException.class)
                .hasMessage("Auth service is unavailable.")
                .hasMessageNotContaining("upstream-secret-body");
        server.verify();
    }

    @Test
    void malformedSuccessFailsClosed() {
        expect(withSuccess("{\"data\":null}", org.springframework.http.MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.checkBusinessListingPermission(TOKEN, BUSINESS_ID))
                .isInstanceOf(AuthServiceClient.DependencyUnavailableException.class);
        server.verify();
    }

    @Test
    void reportCurrentUserLookupPreservesAuthAndDependencyOutcomes() {
        expect(CURRENT_USER_URL, withSuccess("""
                {"data":{"id":"01USER0000000000000000001","status":"ACTIVE"}}
                """, org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(client.requireCurrentUserForReport(TOKEN).status()).isEqualTo("ACTIVE");
        server.verify();

        resetServer();
        expect(CURRENT_USER_URL, withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.requireCurrentUserForReport(TOKEN))
                .isInstanceOf(AuthServiceClient.AuthenticationException.class);
        server.verify();

        resetServer();
        expect(CURRENT_USER_URL, withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.requireCurrentUserForReport(TOKEN))
                .isInstanceOf(ListingAuthorizationException.class);
        server.verify();

        resetServer();
        expect(CURRENT_USER_URL, withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.requireCurrentUserForReport(TOKEN))
                .isInstanceOf(AuthServiceClient.DependencyUnavailableException.class);
        server.verify();

        resetServer();
        expect(CURRENT_USER_URL, withException(new SocketTimeoutException("upstream-secret-body")));
        assertThatThrownBy(() -> client.requireCurrentUserForReport(TOKEN))
                .isInstanceOf(AuthServiceClient.DependencyUnavailableException.class)
                .hasMessageNotContaining("upstream-secret-body");
        server.verify();
    }

    // Installs the exact authenticated membership request expectation without exposing the bearer.
    private void expect(org.springframework.test.web.client.ResponseCreator responseCreator) {
        expect(URL, responseCreator);
    }

    private void expect(
            String url,
            org.springframework.test.web.client.ResponseCreator responseCreator) {
        server.expect(once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(responseCreator);
    }

    private void resetServer() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestAuthServiceClient(builder, "http://auth.test");
    }
}
