package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ListingDiscoveryEmbeddingSourceControllerTests {

    private static final String REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";

    @Mock ListingDiscoveryEmbeddingSourceService service;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ListingDiscoveryEmbeddingSourceController(
                        service,
                        org.mockito.Mockito.mock(ListingDiscoveryEmbeddingResultService.class)))
                .setControllerAdvice(new ListingDiscoveryEmbeddingExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void exactRequestRouteReturnsStrictPublicSourceAndCorrelation() throws Exception {
        when(service.readExact("agent-token", REQUEST_ID)).thenReturn(response());

        mockMvc.perform(get(
                        "/api/v1/internal/agent/discovery/embedding-requests/{requestId}/source",
                        REQUEST_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .header("X-Correlation-Id", "corr-embedding-source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId", equalTo(REQUEST_ID)))
                .andExpect(jsonPath("$.listingId", equalTo("01ARZ3NDEKTSV4RRFFQ69G5FAD")))
                .andExpect(jsonPath("$.embeddingIdentity.model", equalTo("text-embedding-3-small")))
                .andExpect(jsonPath("$.embeddingText", equalTo("TITLE\nDesk\nCATEGORY\nFurniture\nfurniture\nDESCRIPTION\nPublic description")))
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.publicCity").doesNotExist())
                .andExpect(jsonPath("$.vector").doesNotExist())
                .andExpect(jsonPath("$.providerResponse").doesNotExist());
    }

    @Test
    void stableErrorsDoNotDiscloseResourceOrDependencyDetails() throws Exception {
        when(service.readExact(anyString(), anyString()))
                .thenThrow(new ListingDiscoveryEmbeddingFeatureDisabledException())
                .thenThrow(new ListingAuthorizationException("secret token mismatch"))
                .thenThrow(new IllegalArgumentException("malicious request value"))
                .thenThrow(new ListingDiscoveryEmbeddingSourceNotFoundException())
                .thenThrow(new ListingDiscoveryEmbeddingUnavailableException());

        expect("agent-token", "corr-disabled", 404, "FEATURE_DISABLED");
        expect("wrong-token", "corr-auth", 403, "AGENT_INTERNAL_AUTHENTICATION_REQUIRED");
        expect("agent-token", "corr-invalid", 400, "INVALID_REQUEST");
        expect("agent-token", "corr-hidden", 404, "LISTING_DISCOVERY_EMBEDDING_SOURCE_NOT_FOUND");
        expect("agent-token", "corr-unavailable", 503, "LISTING_DISCOVERY_EMBEDDING_UNAVAILABLE");
    }

    private void expect(
            String token,
            String correlationId,
            int statusCode,
            String errorCode) throws Exception {
        mockMvc.perform(get(
                        "/api/v1/internal/agent/discovery/embedding-requests/{requestId}/source",
                        REQUEST_ID)
                        .header("X-Agent-Internal-Service-Token", token)
                        .header("X-Correlation-Id", correlationId))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.error.code", equalTo(errorCode)))
                .andExpect(jsonPath("$.error.correlationId", equalTo(correlationId)))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    private ListingDiscoveryEmbeddingSourceResponse response() {
        return new ListingDiscoveryEmbeddingSourceResponse(
                "MARKETPLACE_LISTING_EMBEDDING_SOURCE_V1",
                REQUEST_ID,
                "01ARZ3NDEKTSV4RRFFQ69G5FAD",
                7,
                "MARKETPLACE_LISTING_DISCOVERY_V2",
                "a".repeat(64),
                "MARKETPLACE_LISTING_EMBEDDING_TEXT_V1",
                "b".repeat(64),
                "NFKC_WHITESPACE_V1",
                "PUBLIC_CONTACT_REDACTION_V1",
                "und",
                new ListingDiscoveryEmbeddingSourceResponse.EmbeddingIdentity(
                        "openai", "text-embedding-3-small", 1536),
                "TITLE\nDesk\nCATEGORY\nFurniture\nfurniture\nDESCRIPTION\nPublic description");
    }
}
