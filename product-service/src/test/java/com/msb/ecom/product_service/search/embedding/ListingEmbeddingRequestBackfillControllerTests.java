package com.msb.ecom.product_service.search.embedding;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ListingEmbeddingRequestBackfillControllerTests {
    private static final String RUN_ID = "01R00000000000000000000931";
    private final ListingEmbeddingRequestBackfillOperatorService service = mock(
            ListingEmbeddingRequestBackfillOperatorService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new ListingEmbeddingRequestBackfillController(service))
                .setControllerAdvice(new ListingEmbeddingRequestBackfillExceptionHandler())
                .addFilter(new CorrelationIdFilter())
                .build();
    }

    @Test
    void exactRoutesReturnOnlyBoundedStatusContract() throws Exception {
        ListingEmbeddingRequestBackfillResponse response = response("SUCCEEDED", "PENDING");
        when(service.start(anyString())).thenReturn(response);
        when(service.status(RUN_ID)).thenReturn(response);
        when(service.resume(anyString(), anyString())).thenReturn(response);

        mvc.perform(post("/api/v1/admin/search/listings/embedding-request-backfills")
                        .header("X-Correlation-ID", "corr-start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(
                        ListingEmbeddingRequestBackfillResponse.SCHEMA))
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.upperBoundListingId").doesNotExist())
                .andExpect(jsonPath("$.lastProcessedListingId").doesNotExist());
        mvc.perform(get("/api/v1/admin/search/listings/embedding-request-backfills/{runId}",
                        RUN_ID))
                .andExpect(status().isOk());
        mvc.perform(post(
                        "/api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume",
                        RUN_ID)
                        .header("X-Correlation-ID", "corr-resume"))
                .andExpect(status().isOk());
    }

    @Test
    void bodylessFixedLengthStartAndResumeAreAccepted() throws Exception {
        ListingEmbeddingRequestBackfillResponse response = response("SUCCEEDED", "PENDING");
        when(service.start(anyString())).thenReturn(response);
        when(service.resume(anyString(), anyString())).thenReturn(response);

        mvc.perform(post("/api/v1/admin/search/listings/embedding-request-backfills")
                        .header(HttpHeaders.CONTENT_LENGTH, "0"))
                .andExpect(status().isOk());
        mvc.perform(post(
                        "/api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume",
                        RUN_ID)
                        .header(HttpHeaders.CONTENT_LENGTH, "0"))
                .andExpect(status().isOk());

        verify(service).start(anyString());
        verify(service).resume(anyString(), anyString());
    }

    @Test
    void emptyChunkedStartAndResumeAreAccepted() throws Exception {
        ListingEmbeddingRequestBackfillResponse response = response("SUCCEEDED", "PENDING");
        when(service.start(anyString())).thenReturn(response);
        when(service.resume(anyString(), anyString())).thenReturn(response);

        mvc.perform(post("/api/v1/admin/search/listings/embedding-request-backfills")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());
        mvc.perform(post(
                        "/api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked"))
                .andExpect(status().isOk());

        verify(service).start(anyString());
        verify(service).resume(anyString(), anyString());
    }

    @Test
    void nonemptyFixedLengthBodyIsRejectedBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/api/v1/admin/search/listings/embedding-request-backfills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void nonemptyChunkedBodyIsRejectedForStartAndResumeBeforeServiceInvocation()
            throws Exception {
        mvc.perform(post("/api/v1/admin/search/listings/embedding-request-backfills")
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));

        mvc.perform(post(
                        "/api/v1/admin/search/listings/embedding-request-backfills/{runId}/resume",
                        RUN_ID)
                        .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("LISTING_INVALID_REQUEST"));

        verifyNoInteractions(service);
    }

    @Test
    void stableErrorsNeverExposeExceptionDetails() throws Exception {
        when(service.status(RUN_ID)).thenThrow(new ListingEmbeddingRequestBackfillException(
                ListingEmbeddingRequestBackfillException.Kind.UNAVAILABLE,
                "LISTING_EMBEDDING_BACKFILL_UNAVAILABLE",
                new IllegalStateException("private row detail")));

        mvc.perform(get("/api/v1/admin/search/listings/embedding-request-backfills/{runId}",
                        RUN_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("LISTING_EMBEDDING_BACKFILL_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message")
                        .value("Listing embedding backfill is temporarily unavailable."));
    }

    private ListingEmbeddingRequestBackfillResponse response(
            String outcome,
            String state) {
        Instant now = Instant.parse("2026-07-24T02:00:00Z");
        return new ListingEmbeddingRequestBackfillResponse(
                ListingEmbeddingRequestBackfillResponse.SCHEMA,
                RUN_ID,
                outcome,
                state,
                1,
                2,
                1,
                1,
                0,
                0,
                null,
                now,
                now,
                null);
    }
}
