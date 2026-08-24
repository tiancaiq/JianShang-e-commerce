package com.msb.ecom.product_service.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalProductAnalyticsControllerTests {
    private static final Instant FROM = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-02T01:00:00Z");

    private ProductAnalyticsService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ProductAnalyticsService.class);
        InternalProductAnalyticsController controller = new InternalProductAnalyticsController(service);
        ReflectionTestUtils.setField(controller, "expectedToken", "analytics-secret");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsMissingInternalTokenBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/analytics/summary")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsIncorrectInternalTokenBeforeCallingService() throws Exception {
        mockMvc.perform(get("/api/v1/internal/admin/analytics/summary")
                        .header("X-Internal-Service-Token", "wrong")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void returnsTypedSummaryForCorrectInternalToken() throws Exception {
        when(service.summary(FROM, TO)).thenReturn(summary());

        mockMvc.perform(get("/api/v1/internal/admin/analytics/summary")
                        .header("X-Internal-Service-Token", "analytics-secret")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.timezone").value("UTC"))
                .andExpect(jsonPath("$.listings.totalListings").value(12))
                .andExpect(jsonPath("$.moderation.rejectionRate").value(0.25))
                .andExpect(jsonPath("$.listingEnforcement.targetType").value("LISTING"))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void defaultsListingCreatedTrendToDailyBuckets() throws Exception {
        ListingCreatedTrend trend = new ListingCreatedTrend("LISTINGS_CREATED",
                new AnalyticsRange(FROM, TO, "UTC"), Granularity.DAY,
                List.of(new TrendPoint(FROM, TO, 3)), 3, GENERATED_AT);
        when(service.listingCreatedTrend(FROM, TO, Granularity.DAY)).thenReturn(trend);

        mockMvc.perform(get("/api/v1/internal/admin/analytics/trends/listings-created")
                        .header("X-Internal-Service-Token", "analytics-secret")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("LISTINGS_CREATED"))
                .andExpect(jsonPath("$.granularity").value("DAY"))
                .andExpect(jsonPath("$.points[0].value").value(3));
    }

    private Summary summary() {
        return new Summary(
                new AnalyticsRange(FROM, TO, "UTC"),
                new ListingStateSummary(12, 1, 2, 3, 1, 1, 1, 1, 1, 1, 4),
                new ModerationSummary(4, 3, 1, FROM, 3600L, 2, 60L,
                        4, 2, 1, 1, new BigDecimal("0.2500")),
                new CategorySummary(List.of(), List.of()),
                new ListingEnforcementSummary("LISTING", 2, 1, List.of(), List.of()),
                GENERATED_AT);
    }
}
