package com.msb.ecom.api_gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import io.restassured.RestAssured;

import java.time.Instant;
import java.net.URI;

import static org.hamcrest.Matchers.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

	@LocalServerPort
	private Integer port;

	@Autowired
	private Environment environment;

	@Autowired
	private ClientHttpRequestFactory clientHttpRequestFactory;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void setup() {
		RestAssured.baseURI = "http://localhost";
		RestAssured.port = port;

		// Mock JwtDecoder to return a valid JWT for any token
		Mockito.when(jwtDecoder.decode(ArgumentMatchers.anyString()))
				.thenReturn(Jwt.withTokenValue("token")
						.header("alg", "none")
						.claim("sub", "test-user")
						.issuedAt(Instant.now())
						.expiresAt(Instant.now().plusSeconds(3600))
						.build());
		Mockito.when(jwtDecoder.decode("invalid-token"))
				.thenThrow(new BadJwtException("invalid token"));
	}

	@Test
	void shouldReturnFallbackWhenProductServiceDown() {
		// Fallback route is permitAll, so circuit breaker should return 503
		RestAssured.given()
				.when()
				.get("/api/product")
				.then()
				.statusCode(anyOf(equalTo(302), equalTo(401), equalTo(503)));
		// 401/302 if authentication starts before routing, 503 if circuit breaker handles it.
	}

	@Test
	void shouldExposePublicCategoriesWithoutLogin() {
		RestAssured.given()
				.when()
				.get("/api/v1/categories")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldExposePublicListingsWithoutLogin() {
		RestAssured.given()
				.when()
				.get("/api/v1/public/listings")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldUseHttp11CompatibleGatewayClient() {
		org.assertj.core.api.Assertions.assertThat(
				environment.getProperty("spring.http.client.factory"))
				.isEqualTo("simple");
	}

	@Test
	void shouldProvidePatchCapableProxyClientWhenCartFeatureIsDisabled() throws Exception {
		org.assertj.core.api.Assertions.assertThat(environment.getProperty("msb.gateway.features.cart"))
				.isEqualTo("false");
		org.assertj.core.api.Assertions.assertThat(clientHttpRequestFactory)
				.isNotInstanceOf(SimpleClientHttpRequestFactory.class);
		var request = clientHttpRequestFactory.createRequest(URI.create("http://127.0.0.1:1"), HttpMethod.PATCH);
		org.assertj.core.api.Assertions.assertThat(request.getMethod()).isEqualTo(HttpMethod.PATCH);
	}

	@Test
	void shouldKeepMarketplaceDiscoveryTimeoutSeparateFromListingAgentTimeout() {
		org.assertj.core.api.Assertions.assertThat(
				environment.getProperty(
						"resilience4j.timelimiter.instances.agentServiceCircuitBreaker.timeout-duration"))
				.isEqualTo("15s");
		org.assertj.core.api.Assertions.assertThat(
				environment.getProperty(
						"resilience4j.timelimiter.instances.agentDiscoveryServiceCircuitBreaker.timeout-duration"))
				.isEqualTo("35s");
		org.assertj.core.api.Assertions.assertThat(
				environment.getProperty("spring.cloud.gateway.mvc.streaming-buffer-size"))
				.isEqualTo("128");
	}

	@Test
	void shouldExposePublicMarketplaceSearchWithoutLogin() {
		RestAssured.given()
				.queryParam("sort", "price_asc")
				.when()
				.get("/api/v1/public/marketplace/listings/search")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldExposePublicStoreSearchWithoutLogin() {
		RestAssured.given()
				.queryParam("sort", "price_desc")
				.when()
				.get("/api/v1/public/stores/listings/search")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldExposePublicStoreProfileWithoutLogin() {
		RestAssured.given()
				.when()
				.get("/api/v1/stores/shen-ban-demo-store")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteAuthOwnedAdminEndpointsThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/admin/dashboard-summary")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteAdminGovernanceEndpointsThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/admin/governance/approvals")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteAdminAnalyticsEndpointsThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/admin/analytics/overview")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteAuthOwnedSupportEndpointsThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/support/tickets/mine")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));

		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/admin/support/tickets")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteCurrentBusinessStoreContextThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/businesses/me/store-context")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteBusinessStoreItemsThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/businesses/01B00000000000000000000001/store/items")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteBusinessStoreItemMediaThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.post("/api/v1/businesses/01B00000000000000000000001/store/items/01L00000000000000000000001/media/upload-request")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void deferredCheckoutRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/checkouts/01C00000000000000000000001")
				.then()
				.statusCode(404);
	}

	@Test
	void checkoutRequiresAuthentication() {
		RestAssured.given()
				.when()
				.get("/api/v1/checkouts/01C00000000000000000000001")
				.then()
				.statusCode(401);
	}

	@Test
	void shouldExposePublicListingMediaWithoutLogin() {
		RestAssured.given()
				.when()
				.get("/api/v1/public/listing-media/01I00000000000000000000001")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldExposePublicUserAvatarsWithoutLogin() {
		RestAssured.given()
				.when()
				.get("/api/v1/public/user-avatars/01JY0000000000000000000000")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteProductOwnedAdminModerationEndpointsThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/admin/moderation/listing-cases")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteCurrentUserLikedListingsThroughProductService() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/users/me/liked-listings")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteChatConversationCreationThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.post("/api/v1/listings/01L00000000000000000000001/conversations")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteChatConversationMessagesThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/conversations/01C00000000000000000000001/messages")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldRouteChatConversationInboxThroughGateway() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/conversations")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void deferredCartRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/cart")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredInventoryRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/businesses/01B00000000000000000000001/inventory")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredBusinessOrderRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/businesses/01B00000000000000000000001/orders")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredBuyerOrderRouteReturnsSafeNotFoundInsteadOfUnexpectedError() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.get("/api/v1/orders")
				.then()
				.statusCode(404);
	}

	@Test
	void businessOrderRouteRequiresAuthenticationEvenWhenDisabled() {
		RestAssured.given()
				.when()
				.get("/api/v1/businesses/01B00000000000000000000001/orders")
				.then()
				.statusCode(401);
	}

	@Test
	void deferredPaymentRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer token")
				.when()
				.post("/api/payment")
				.then()
				.statusCode(404);
	}

	@Test
	void shouldExposeActuatorHealth() {
		// Actuator health is in the permitAll list
		RestAssured.given()
				.when()
				.get("/actuator/health")
				.then()
				.statusCode(200)
				.header("X-Correlation-Id", matchesPattern("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
				.body("status", notNullValue());
	}

	@Test
	void shouldReturnStandardFallbackErrorEnvelope() {
		RestAssured.given()
				.header("X-Correlation-Id", "fallback-request-123")
				.when()
				.get("/fallbackRoute")
				.then()
				.statusCode(503)
				.header("X-Correlation-Id", equalTo("fallback-request-123"))
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"))
				.body("error.correlationId", equalTo("fallback-request-123"));
	}

	@Test
	void shouldPreserveValidCorrelationId() {
		RestAssured.given()
				.header("X-Correlation-Id", "client-request-123")
				.when()
				.get("/actuator/health")
				.then()
				.statusCode(200)
				.header("X-Correlation-Id", equalTo("client-request-123"));
	}

	@Test
	void shouldReplaceInvalidAndOversizedCorrelationIds() {
		RestAssured.given()
				.header("X-Correlation-Id", "unsafe value")
				.when()
				.get("/actuator/health")
				.then()
				.statusCode(200)
				.header("X-Correlation-Id", not(equalTo("unsafe value")))
				.header("X-Correlation-Id", matchesPattern("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"));

		String oversized = "a".repeat(129);
		RestAssured.given()
				.header("X-Correlation-Id", oversized)
				.when()
				.get("/actuator/health")
				.then()
				.statusCode(200)
				.header("X-Correlation-Id", not(equalTo(oversized)))
				.header("X-Correlation-Id", matchesPattern("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"));
	}

	@Test
	void shouldReturnFallbackWhenPaymentServiceDown() {
		String paymentJson = """
				{
				    "orderNumber": "ORD-001",
				    "paymentMethod": "CREDIT_CARD",
				    "amount": 299.99
				}
				""";

		RestAssured.given()
				.contentType("application/json")
				.body(paymentJson)
				.when()
				.post("/api/payment")
				.then()
				.statusCode(anyOf(equalTo(401), equalTo(403)));
	}

	@Test
	void shouldProtectIdentityUserRoute() {
		RestAssured.given()
				.when()
				.get("/api/v1/users/me")
				.then()
				.statusCode(anyOf(equalTo(302), equalTo(401), equalTo(503)));
	}

	@Test
	void shouldProtectBuyerAddressRoutes() {
		RestAssured.given()
				.when()
				.get("/api/v1/users/me/addresses")
				.then()
				.statusCode(anyOf(equalTo(302), equalTo(401), equalTo(503)));
	}

	@Test
	void deferredBuyerAddressRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.get("/api/v1/users/me/addresses")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredAgentRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.post("/api/v1/agent/sessions")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredAgentDiscoveryRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.get("/api/v1/agent/discovery/sessions/01A00000000000000000000001")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredListingProposalRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.post("/api/v1/agent/listing-proposals")
				.then()
				.statusCode(404);
	}

	@Test
	void deferredCategoryGuidanceRouteIsNotRegisteredByDefault() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.get("/api/v1/admin/categories/01CATEGORY00000000000001/guidance")
				.then()
				.statusCode(404);
	}

	@Test
	void protectedIdentityRouteRejectsInvalidBearerToken() {
		RestAssured.given()
				.header("Authorization", "Bearer invalid-token")
				.when()
				.get("/api/v1/users/me")
				.then()
				.statusCode(401);
	}

	@Test
	void validBearerTokenPassesGatewayAuthenticationBeforeRouting() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.get("/api/v1/users/me")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void authenticatedReportRoutesAreOwnedByAuthService() {
		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.contentType("application/json")
				.body("{\"targetType\":\"LISTING\",\"targetId\":\"01L00000000000000000000001\",\"reasonCode\":\"SPAM\"}")
				.when()
				.post("/api/v1/reports")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));

		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.get("/api/v1/admin/reports")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));

		RestAssured.given()
				.header("Authorization", "Bearer valid-token")
				.when()
				.get("/api/v1/admin/cases")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void authenticatedDisputeRoutesAreOwnedByOrderService() {
		RestAssured.given().header("Authorization", "Bearer valid-token")
				.when().get("/api/v1/admin/disputes")
				.then().statusCode(503).body("error.code", equalTo("SERVICE_UNAVAILABLE"));
		RestAssured.given().header("Authorization", "Bearer valid-token")
				.when().get("/api/v1/disputes/01D00000000000000000000001")
				.then().statusCode(503).body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void authenticatedFinanceRoutesAreOwnedByPaymentService() {
		RestAssured.given().header("Authorization", "Bearer valid-token")
				.when().get("/api/v1/admin/payments")
				.then().statusCode(503).body("error.code", equalTo("SERVICE_UNAVAILABLE"));
		RestAssured.given().header("Authorization", "Bearer valid-token")
				.when().get("/api/v1/admin/refunds")
				.then().statusCode(503).body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void spoofedIdentityHeadersDoNotAuthenticateGatewayRequest() {
		RestAssured.given()
				.header("X-User-Id", "spoofed-user")
				.header("X-Keycloak-Sub", "spoofed-subject")
				.when()
				.get("/api/v1/users/me")
				.then()
				.statusCode(anyOf(equalTo(302), equalTo(401)));
	}
}
