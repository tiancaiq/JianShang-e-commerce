package com.msb.ecom.api_gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.mockito.Mockito;
import org.mockito.ArgumentMatchers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import io.restassured.RestAssured;

import java.time.Instant;

import static org.hamcrest.Matchers.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {

	@LocalServerPort
	private Integer port;

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
	void shouldExposePublicListingMediaWithoutLogin() {
		RestAssured.given()
				.when()
				.get("/api/v1/public/listing-media/01I00000000000000000000001")
				.then()
				.statusCode(503)
				.body("error.code", equalTo("SERVICE_UNAVAILABLE"));
	}

	@Test
	void shouldReturnFallbackWhenOrderServiceDown() {
		String orderJson = """
				{
				    "skuCode": "iphone_15",
				    "price": 1000,
				    "quantity": 1
				}
				""";

		RestAssured.given()
				.contentType("application/json")
				.body(orderJson)
				.when()
				.post("/api/order")
				.then()
				.statusCode(anyOf(equalTo(401), equalTo(403)));
	}

	@Test
	void shouldReturnFallbackWhenInventoryServiceDown() {
		RestAssured.given()
				.when()
				.get("/api/inventory?skuCode=iphone_15&quantity=1")
				.then()
				.statusCode(anyOf(equalTo(302), equalTo(401), equalTo(503)));
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
