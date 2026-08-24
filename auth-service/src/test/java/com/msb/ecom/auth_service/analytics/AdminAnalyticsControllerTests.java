package com.msb.ecom.auth_service.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminAnalyticsControllerTests {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AdminAnalyticsService service = mock(AdminAnalyticsService.class);
        AnalyticsRangeResolver ranges = new AnalyticsRangeResolver(Clock.fixed(
                Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new AdminAnalyticsController(service, ranges))
                .setControllerAdvice(new AdminAnalyticsExceptionHandler()).build();
    }

    @Test
    void rejectsCustomRangeWithoutBothBoundaries() throws Exception {
        mvc.perform(get("/api/v1/admin/analytics/overview")
                        .queryParam("range", "CUSTOM")
                        .queryParam("from", "2026-08-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ANALYTICS_REQUEST_INVALID"));
    }

    @Test
    void rejectsHourlyTrendBeyondFortyEightHours() throws Exception {
        mvc.perform(get("/api/v1/admin/analytics/trends")
                        .queryParam("metric", "REPORTS_SUBMITTED")
                        .queryParam("range", "LAST_7_DAYS")
                        .queryParam("granularity", "HOUR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ANALYTICS_REQUEST_INVALID"));
    }
}
