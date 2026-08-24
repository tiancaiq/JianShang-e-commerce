package com.msb.ecom.payment_service.analytics;

import com.msb.ecom.payment_service.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalPaymentAnalyticsController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "commerce.internal-service-token=analytics-test-token")
class PaymentAnalyticsSecurityTests {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PaymentAnalyticsService service;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void internalMatcherPermitsControllerTokenAuthenticationWithoutBearerJwt() throws Exception {
        mvc.perform(get("/api/v1/internal/admin/analytics/summary")
                        .header("X-Internal-Service-Token", "analytics-test-token")
                        .param("from", "2026-08-16T00:00:00Z")
                        .param("to", "2026-08-23T00:00:00Z"))
                .andExpect(status().isOk());
    }
}
