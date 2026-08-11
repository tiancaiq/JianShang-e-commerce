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
class ReturnDisabledRouteTests {
    @LocalServerPort private Integer port;
    @MockitoBean private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
        when(jwtDecoder.decode(anyString())).thenReturn(Jwt.withTokenValue("return-disabled-token")
                .header("alg", "none").claim("sub", "buyer-subject")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }

    @Test
    void disabledReturnNamespaceIsOwnedLocally() {
        RestAssured.given().header("Authorization", "Bearer return-disabled-token")
                .when().get("/api/v1/orders/01O00000000000000000000001/groups/"
                        + "01G00000000000000000000001/return")
                .then().statusCode(404);
        RestAssured.given().header("Authorization", "Bearer return-disabled-token")
                .when().post("/api/v1/businesses/01B00000000000000000000001/orders/"
                        + "01G00000000000000000000001/returns/01R00000000000000000000001/authorize")
                .then().statusCode(404);
    }
}
