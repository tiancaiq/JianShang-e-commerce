package com.msb.ecom.product_service.search.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ListingDiscoveryEmbeddingResultControllerTests {

    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";

    @Mock ListingDiscoveryEmbeddingSourceService sourceService;
    @Mock ListingDiscoveryEmbeddingResultService resultService;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ListingDiscoveryEmbeddingSourceController(
                        sourceService,
                        resultService))
                .setControllerAdvice(new ListingDiscoveryEmbeddingExceptionHandler())
                .build();
    }

    @Test
    void exactResultRouteReturnsStrictAcknowledgement() throws Exception {
        when(resultService.accept(eq("agent-token"), eq(REQUEST_ID), any()))
                .thenReturn(new ListingDiscoveryEmbeddingResultAcknowledgement(
                        "MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1",
                        REQUEST_ID,
                        "ACCEPTED"));

        mockMvc.perform(post(
                        "/api/v1/internal/agent/discovery/embedding-requests/{requestId}/result",
                        REQUEST_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.schemaVersion")
                        .value("MARKETPLACE_LISTING_EMBEDDING_RESULT_ACK_V1"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.outcome").value("ACCEPTED"));
    }

    @Test
    void stableConflictsMatchTheAgent04bClientContract() throws Exception {
        when(resultService.accept(any(), any(), any()))
                .thenThrow(
                        new ListingDiscoveryEmbeddingStaleException(),
                        new ListingDiscoveryEmbeddingIdentityConflictException(),
                        new ListingDiscoveryEmbeddingIdempotencyConflictException());

        assertConflict("LISTING_DISCOVERY_EMBEDDING_STALE");
        assertConflict("LISTING_DISCOVERY_EMBEDDING_IDENTITY_CONFLICT");
        assertConflict("LISTING_DISCOVERY_EMBEDDING_IDEMPOTENCY_CONFLICT");
    }

    @Test
    void disabledAuthenticationInvalidAndUnavailableUseSafeEnvelopes() throws Exception {
        when(resultService.accept(any(), any(), any()))
                .thenThrow(
                        new ListingDiscoveryEmbeddingFeatureDisabledException(),
                        new com.msb.ecom.product_service.model.ListingAuthorizationException(
                                "secret"),
                        new IllegalArgumentException("secret body"),
                        new ListingDiscoveryEmbeddingUnavailableException());

        assertError(404, "FEATURE_DISABLED");
        assertError(403, "AGENT_INTERNAL_AUTHENTICATION_REQUIRED");
        assertError(400, "INVALID_REQUEST");
        assertError(503, "LISTING_DISCOVERY_EMBEDDING_UNAVAILABLE");
    }

    private void assertConflict(String code) throws Exception {
        assertError(409, code);
    }

    private void assertError(int status, String code) throws Exception {
        mockMvc.perform(post(
                        "/api/v1/internal/agent/discovery/embedding-requests/{requestId}/result",
                        REQUEST_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.message").isString());
    }
}
