package com.msb.ecom.product_service.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AuthServiceClient;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ListingReportControllerTests {

    private static final String REPORT_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAD";
    private static final String LISTING_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAC";

    @Mock
    ListingReportService service;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ListingReportRequestParser parser = new ListingReportRequestParser(
                new ObjectMapper().findAndRegisterModules(),
                Validation.buildDefaultValidatorFactory().getValidator());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ListingReportController(service, parser))
                .setControllerAdvice(new ListingReportExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void createReturnsOnlySanitizedRepresentation() throws Exception {
        doNothing().when(service).requireEnabled();
        ListingReportResponse response = response();
        when(service.create(any(), anyString(), anyString()))
                .thenReturn(new ListingReportService.CreateResult(response, true));

        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-key-0001")
                        .header("X-Correlation-Id", "corr-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportId", equalTo(REPORT_ID)))
                .andExpect(jsonPath("$.status", equalTo("RECEIVED")))
                .andExpect(jsonPath("$.reporterUserId").doesNotExist())
                .andExpect(jsonPath("$.statement").doesNotExist())
                .andExpect(jsonPath("$.moderationCaseId").doesNotExist())
                .andExpect(jsonPath("$.routingQueue").doesNotExist())
                .andExpect(jsonPath("$.seller").doesNotExist());
    }

    @Test
    void disabledCapabilityAndCrossActorReadsUseHiddenNotFound() throws Exception {
        doThrow(new ListingReportFeatureDisabledException()).when(service).requireEnabled();

        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_NOT_FOUND")));

        when(service.get(REPORT_ID)).thenThrow(new ListingReportNotFoundException());
        mockMvc.perform(get("/api/v1/reports/{reportId}", REPORT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_NOT_FOUND")));
    }

    @Test
    void payloadLimitUnknownFieldsAndRateRetryAreStable() throws Exception {
        doNothing().when(service).requireEnabled();

        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[ListingReportRequestParser.MAX_JSON_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_PAYLOAD_TOO_LARGE")));

        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson().replace("\"reasonCode\"", "\"provider\":\"x\",\"reasonCode\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_INVALID_REQUEST")));

        when(service.create(any(), anyString(), anyString()))
                .thenThrow(new ListingReportRateLimitException(37));
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-key-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"));
    }

    @Test
    void authAndDependencyFailuresUseSafeStableEnvelopes() throws Exception {
        doNothing().when(service).requireEnabled();

        doThrow(new AuthServiceClient.AuthenticationException())
                .when(service).create(any(), anyString(), anyString());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-auth-key-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code", equalTo("AUTHENTICATION_REQUIRED")))
                .andExpect(jsonPath("$.error.message", equalTo("Authentication is required.")));

        doThrow(new ListingAuthorizationException("upstream-secret-body"))
                .when(service).create(any(), anyString(), anyString());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-auth-key-0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_FORBIDDEN")))
                .andExpect(jsonPath("$.error.message", equalTo("Authenticated report access is required.")));

        doThrow(new AuthServiceClient.DependencyUnavailableException())
                .when(service).create(any(), anyString(), anyString());
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", "report-auth-key-0003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code", equalTo("LISTING_REPORT_UNAVAILABLE")))
                .andExpect(jsonPath("$.error.message", equalTo(
                        "Listing report intake is temporarily unavailable.")));
    }

    private ListingReportResponse response() {
        return new ListingReportResponse(
                REPORT_ID,
                "RECEIVED",
                LISTING_ID,
                "FRAUD_OR_MISREPRESENTATION",
                "REP-00-V1",
                Instant.parse("2026-07-20T10:00:00Z"),
                Instant.parse("2026-07-20T10:00:00Z"));
    }

    private String validJson() {
        return """
                {
                  "listingId":"%s",
                  "reasonCode":"FRAUD_OR_MISREPRESENTATION",
                  "statement":"The public description appears inconsistent.",
                  "listingMediaIds":[]
                }
                """.formatted(LISTING_ID);
    }
}
