package com.msb.ecom.product_service.search.hybrid;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalAgentHybridSearchControllerTests {

    @Mock ListingHybridSearchService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new InternalAgentHybridSearchController(service))
                .setControllerAdvice(new ListingHybridSearchExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void postsOnlyTheExactInternalRouteAndReturnsStrictSafeEnvelope() throws Exception {
        when(service.search(anyString(), any())).thenReturn(new ListingHybridSearchResponse(
                ListingHybridSearchResponse.SCHEMA_VERSION,
                List.of(),
                new ListingHybridSearchResponse.Meta(
                        "HYBRID", false, Instant.parse("2026-07-23T12:00:00Z"))));

        mockMvc.perform(post("/api/v1/internal/agent/marketplace/listings/hybrid-search")
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .header("X-Correlation-Id", "corr-hybrid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion",
                        equalTo(ListingHybridSearchResponse.SCHEMA_VERSION)))
                .andExpect(jsonPath("$.meta.retrievalMode", equalTo("HYBRID")))
                .andExpect(jsonPath("$.vector").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist());
    }

    @Test
    void serializesTheOptInDiscoverySummaryWithoutInternalScores() throws Exception {
        when(service.search(anyString(), any())).thenReturn(new ListingHybridSearchResponse(
                ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION,
                List.of(),
                new ListingHybridSearchResponse.Meta(
                        "HYBRID", false, Instant.parse("2026-07-23T12:00:00Z")),
                new ListingHybridSearchResponse.Discovery(
                        "chair",
                        25,
                        20,
                        12,
                        8,
                        "HIGH",
                        "RESULTS_AVAILABLE",
                        new ListingHybridSearchResponse.Facets(
                                List.of(
                                        new ListingHybridSearchResponse.FacetValue(
                                                "Dining Chair", 12),
                                        new ListingHybridSearchResponse.FacetValue(
                                                "Gaming Chair", 8)),
                                List.of(),
                                List.of(),
                                List.of()))));

        mockMvc.perform(post("/api/v1/internal/agent/marketplace/listings/hybrid-search")
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion",
                        equalTo(ListingHybridSearchResponse.RESULTS_FIRST_SCHEMA_VERSION)))
                .andExpect(jsonPath("$.discovery.normalizedCategory", equalTo("chair")))
                .andExpect(jsonPath("$.discovery.totalMatches", equalTo(25)))
                .andExpect(jsonPath("$.discovery.relevantMatchCount", equalTo(20)))
                .andExpect(jsonPath("$.discovery.exactMatchCount", equalTo(12)))
                .andExpect(jsonPath("$.discovery.relatedMatchCount", equalTo(8)))
                .andExpect(jsonPath("$.discovery.retrievalConfidence", equalTo("HIGH")))
                .andExpect(jsonPath("$.discovery.facets.subtype[0].value",
                        equalTo("Dining Chair")))
                .andExpect(jsonPath("$.discovery.facets.subtype[1].count", equalTo(8)))
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.discovery.score").doesNotExist());
    }

    @Test
    void preservesTheDeployedV2DiscoveryShapeDuringRollingUpdates() throws Exception {
        when(service.search(anyString(), any())).thenReturn(new ListingHybridSearchResponse(
                ListingHybridSearchResponse.FACET_SCHEMA_VERSION,
                List.of(),
                new ListingHybridSearchResponse.Meta(
                        "HYBRID", false, Instant.parse("2026-07-23T12:00:00Z")),
                new ListingHybridSearchResponse.Discovery(
                        "chair", 0, "CATEGORY_UNAVAILABLE",
                        new ListingHybridSearchResponse.Facets(
                                List.of(), List.of(), List.of(), List.of()))));

        mockMvc.perform(post("/api/v1/internal/agent/marketplace/listings/hybrid-search")
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion",
                        equalTo(ListingHybridSearchResponse.FACET_SCHEMA_VERSION)))
                .andExpect(jsonPath("$.discovery.relevantMatchCount").doesNotExist())
                .andExpect(jsonPath("$.discovery.retrievalConfidence").doesNotExist());
    }

    @Test
    void mapsStableSafeErrorsWithoutEchoingInternalMessages() throws Exception {
        when(service.search(any(), any()))
                .thenThrow(new ListingHybridSearchFeatureDisabledException())
                .thenThrow(new ListingAuthorizationException("secret token"))
                .thenThrow(new IllegalArgumentException("raw query payload"))
                .thenThrow(new ListingHybridSearchIdentityMismatchException())
                .thenThrow(new ListingHybridSearchUnavailableException());

        expect(404, "FEATURE_DISABLED", "corr-disabled");
        expect(403, "AGENT_INTERNAL_AUTHENTICATION_REQUIRED", "corr-auth");
        expect(400, "INVALID_REQUEST", "corr-invalid");
        expect(409, "MARKETPLACE_HYBRID_SEARCH_IDENTITY_MISMATCH", "corr-identity");
        expect(503, "MARKETPLACE_HYBRID_SEARCH_UNAVAILABLE", "corr-unavailable");
    }

    private void expect(int statusCode, String code, String correlation) throws Exception {
        mockMvc.perform(post("/api/v1/internal/agent/marketplace/listings/hybrid-search")
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .header("X-Correlation-Id", correlation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.error.code", equalTo(code)))
                .andExpect(jsonPath("$.error.correlationId", equalTo(correlation)))
                .andExpect(jsonPath("$.error.message", not(containsString("secret"))))
                .andExpect(jsonPath("$.error.message", not(containsString("payload"))));
    }
}
