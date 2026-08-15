package com.msb.ecom.api_gateway;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationDisabledRouteTests {

    @LocalServerPort
    private Integer port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        when(jwtDecoder.decode(anyString())).thenReturn(
                Jwt.withTokenValue("disabled-notification-token")
                        .header("alg", "none")
                        .claim("sub", "notification-user-subject")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build());
    }

    @Test
    void disabledNotificationNamespaceReturnsLocalNotFoundForAuthenticatedRequests() {
        RestAssured.given()
                .header("Authorization", "Bearer disabled-notification-token")
                .when()
                .get("/api/v1/notifications")
                .then()
                .statusCode(404);

        RestAssured.given()
                .header("Authorization", "Bearer disabled-notification-token")
                .when()
                .get("/api/v1/businesses/01K0BUSINESS00000000000000/notifications")
                .then()
                .statusCode(404);
    }
}
