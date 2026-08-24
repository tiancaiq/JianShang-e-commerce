package com.msb.ecom.payment_service.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.msb.ecom.payment_service.analytics.PaymentAnalyticsContracts.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalPaymentAnalyticsControllerTests {
    private static final String TOKEN = "analytics-internal-token";
    private static final Instant FROM = Instant.parse("2026-08-16T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-23T00:00:00Z");
    private final PaymentAnalyticsService service = mock(PaymentAnalyticsService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new InternalPaymentAnalyticsController(service, TOKEN)).build();
        AnalyticsRange range = new AnalyticsRange(FROM, TO, "UTC", null, null);
        when(service.summary(any(), any(), anyString(), anyBoolean(), anyBoolean())).thenReturn(new Summary(
                range, new Window(new PaymentWindow(1, 0, new BigDecimal("100.00"), null),
                        new RefundWindow(1, 1, 0, 1, 0, 1, 0, 0, 0, 0, null)), null,
                new RefundBacklog(0, 0, null, null, null), TO));
        when(service.trend(any(), any(), any(), anyString(), any())).thenReturn(new Trend(
                TrendMetric.REFUNDS_SUCCEEDED, range, Granularity.DAY,
                List.of(new TrendPoint(FROM, 1)), TO));
    }

    @Test
    void missingIncorrectAndUnconfiguredTokensAreRejected() throws Exception {
        mvc.perform(summary()).andExpect(status().isForbidden());
        mvc.perform(summary().header("X-Internal-Service-Token", "wrong"))
                .andExpect(status().isForbidden());

        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(
                new InternalPaymentAnalyticsController(service, " ")).build();
        unconfigured.perform(summary().header("X-Internal-Service-Token", " "))
                .andExpect(status().isForbidden());
    }

    @Test
    void validTokenReturnsNoStoreAggregateWithoutFinancialFieldsByDefault() throws Exception {
        mvc.perform(summary().header("X-Internal-Service-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.current.payments.paymentsSucceeded").value(1))
                .andExpect(jsonPath("$.current.payments.succeededPaymentAmounts").doesNotExist())
                .andExpect(jsonPath("$.current.refunds.amountMetrics").doesNotExist())
                .andExpect(jsonPath("$.currentRefundBacklog.activeAmounts").doesNotExist());
    }

    @Test
    void trendMetricIsAClosedEnum() throws Exception {
        mvc.perform(get("/api/v1/internal/admin/analytics/trends")
                        .header("X-Internal-Service-Token", TOKEN)
                        .param("metric", "SQL_FRAGMENT")
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/internal/admin/analytics/trends")
                        .header("X-Internal-Service-Token", TOKEN)
                        .param("metric", "REFUNDS_SUCCEEDED")
                        .param("from", FROM.toString()).param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("REFUNDS_SUCCEEDED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder summary() {
        return get("/api/v1/internal/admin/analytics/summary")
                .param("from", FROM.toString()).param("to", TO.toString());
    }
}
