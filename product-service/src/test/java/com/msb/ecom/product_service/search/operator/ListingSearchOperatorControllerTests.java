package com.msb.ecom.product_service.search.operator;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ListingSearchOperatorControllerTests {
    private static final String RUN_ID = "01R00000000000000000000801";
    private final ListingSearchOperatorService service = mock(ListingSearchOperatorService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ListingSearchOperatorController(service))
                .setControllerAdvice(new ListingSearchOperatorExceptionHandler())
                .addFilter(new CorrelationIdFilter())
                .build();
    }

    @Test
    void exactVersionedRoutesMapToBoundedServiceCommands() throws Exception {
        ListingSearchOperatorResponse response = response("SUCCEEDED", "CATCHING_UP");
        when(service.prepare(anyString())).thenReturn(response);
        when(service.status(RUN_ID)).thenReturn(response("OBSERVED", "CATCHING_UP"));
        when(service.catchUp(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response);
        when(service.promote(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response("SUCCEEDED", "PROMOTED"));
        when(service.recover(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response("REPLAYED", "PROMOTED"));

        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        "MARKETPLACE_LISTING_VECTOR_REBUILD_STATUS_V2"))
                .andExpect(jsonPath("$.canCatchUp").value(true))
                .andExpect(jsonPath("$.canPromote").value(true))
                .andExpect(jsonPath("$.canRecover").value(false));
        mvc.perform(get("/api/v1/admin/search/listings/vector-rebuilds/{runId}", RUN_ID))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up", RUN_ID))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote", RUN_ID))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/recover", RUN_ID))
                .andExpect(status().isOk());
    }

    @Test
    void commandBodyIsRejectedBeforeAnyOperatorAction() throws Exception {
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds")
                        .contentType("application/json")
                        .content("{\"index\":\"attacker-controlled\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));

        verify(service, never()).prepare(anyString());
    }

    @Test
    void bodylessFixedLengthCommandsAreAccepted() throws Exception {
        ListingSearchOperatorResponse response = response("SUCCEEDED", "CATCHING_UP");
        when(service.prepare(anyString())).thenReturn(response);
        when(service.catchUp(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response);
        when(service.promote(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response("SUCCEEDED", "PROMOTED"));
        when(service.recover(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response("REPLAYED", "PROMOTED"));

        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds")
                        .header(HttpHeaders.CONTENT_LENGTH, "0"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up",
                        RUN_ID)
                        .header(HttpHeaders.CONTENT_LENGTH, "0"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote",
                        RUN_ID)
                        .header(HttpHeaders.CONTENT_LENGTH, "0"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/recover",
                        RUN_ID)
                        .header(HttpHeaders.CONTENT_LENGTH, "0"))
                .andExpect(status().isOk());

        verify(service).prepare(anyString());
        verify(service).catchUp(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString());
        verify(service).promote(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString());
        verify(service).recover(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString());
    }

    @Test
    void emptyChunkedCommandsAreAccepted() throws Exception {
        ListingSearchOperatorResponse response = response("SUCCEEDED", "CATCHING_UP");
        when(service.prepare(anyString())).thenReturn(response);
        when(service.catchUp(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response);
        when(service.promote(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response("SUCCEEDED", "PROMOTED"));
        when(service.recover(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenReturn(response("REPLAYED", "PROMOTED"));

        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/recover",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());

        verify(service).prepare(anyString());
        verify(service).catchUp(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString());
        verify(service).promote(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString());
        verify(service).recover(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString());
    }

    @Test
    void nonemptyFixedLengthCommandsAreRejectedBeforeServiceInvocation()
            throws Exception {
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up",
                        RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote",
                        RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/recover",
                        RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void nonemptyChunkedCommandsAreRejectedBeforeServiceInvocation()
            throws Exception {
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/catch-up",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));
        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/recover",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void safeErrorsPreserveCorrelationWithoutBodiesOrTopology() throws Exception {
        when(service.promote(org.mockito.ArgumentMatchers.eq(RUN_ID), anyString()))
                .thenThrow(new ListingSearchOperatorConflictException(
                        "VECTOR_REBUILD_STATE_CONFLICT"));

        mvc.perform(post("/api/v1/admin/search/listings/vector-rebuilds/{runId}/promote", RUN_ID)
                        .header("X-Correlation-Id", "operator-correlation-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(
                        "VECTOR_REBUILD_STATE_CONFLICT"))
                .andExpect(jsonPath("$.error.correlationId").value(
                        "operator-correlation-1"))
                .andExpect(jsonPath("$").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("vector"))));
    }

    private ListingSearchOperatorResponse response(String outcome, String state) {
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        return new ListingSearchOperatorResponse(
                ListingSearchOperatorResponse.SCHEMA,
                RUN_ID,
                outcome,
                state,
                "marketplace-public-listing-v2-vector",
                "PROMOTED".equals(state) ? "ACTIVE_V2" : "INACTIVE_V2_CANDIDATE",
                "PROMOTED".equals(state) ? "RETAINED_PREVIOUS" : "ACTIVE_PREVIOUS",
                5,
                3,
                2,
                2,
                "CATCHING_UP".equals(state),
                "CATCHING_UP".equals(state),
                false,
                null,
                now,
                now,
                "PROMOTED".equals(state) ? now : null);
    }
}
