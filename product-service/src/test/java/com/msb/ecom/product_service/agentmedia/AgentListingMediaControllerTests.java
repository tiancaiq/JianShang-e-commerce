package com.msb.ecom.product_service.agentmedia;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentListingMediaControllerTests {

    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";
    private static final String MEDIA_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAE";

    @Mock
    AgentListingMediaService service;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentListingMediaController(service))
                .setControllerAdvice(new AgentListingMediaExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void strictInternalRouteReturnsNoStorageOrSellerFields() throws Exception {
        when(service.readOwnedDraftMedia(
                eq("agent-token"),
                eq(LISTING_ID),
                any(AgentListingMediaRequest.class),
                anyString()))
                .thenReturn(new AgentListingMediaResponse(
                        "ai-list-owned-draft-media-v1",
                        LISTING_ID,
                        "12",
                        "OWNED_DRAFT",
                        List.of(new AgentListingMediaResponse.Media(
                                MEDIA_ID,
                                "image/png",
                                8,
                                "a".repeat(64),
                                "7",
                                "iVBORw0KGgo="))));

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .header("X-Correlation-Id", "corr-controller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId", equalTo(LISTING_ID)))
                .andExpect(jsonPath("$.media[0].mediaId", equalTo(MEDIA_ID)))
                .andExpect(jsonPath("$.media[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$.media[0].url").doesNotExist())
                .andExpect(jsonPath("$.actorUserId").doesNotExist())
                .andExpect(jsonPath("$.seller").doesNotExist());
    }

    @Test
    void unknownFieldsAndMalformedIdsAreRejectedByTheRouteSchema() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson().replace(
                                "\"mediaIds\"",
                                "\"storageKey\":\"secret\",\"mediaIds\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.error.code",
                        equalTo("AGENT_LISTING_MEDIA_INVALID_REQUEST")));

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson().replace(MEDIA_ID, "bad-id")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crossActorStyleDenialUsesHiddenNotFoundEnvelope() throws Exception {
        when(service.readOwnedDraftMedia(
                anyString(),
                eq(LISTING_ID),
                any(AgentListingMediaRequest.class),
                anyString()))
                .thenThrow(new AgentListingMediaNotFoundException());

        mockMvc.perform(post(
                        "/api/v1/internal/agent/listings/{listingId}/draft-media",
                        LISTING_ID)
                        .header("X-Agent-Internal-Service-Token", "agent-token")
                        .header("X-Correlation-Id", "corr-hidden")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath(
                        "$.error.code",
                        equalTo("AGENT_LISTING_MEDIA_NOT_FOUND")))
                .andExpect(jsonPath("$.error.correlationId", equalTo("corr-hidden")));
    }

    private String requestJson() {
        return """
                {
                  "schemaVersion": "ai-list-owned-draft-media-v1",
                  "actorUserId": "01ARZ3NDEKTSV4RRFFQ69G5FAA",
                  "mediaIds": ["%s"]
                }
                """.formatted(MEDIA_ID);
    }
}
