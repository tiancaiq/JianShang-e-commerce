package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.appeals.AppealContracts.FinalOutcome;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionPreview;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAppealResolutionControllerTests {
    private static final String APPEAL = "01ARZ3NDEKTSV4RRFFQ69G5AAD";
    @Mock AppealService appealService;
    @Mock AppealResolutionService resolutionService;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminAppealController(appealService, resolutionService))
                .setControllerAdvice(new AppealExceptionHandler()).build();
    }

    @Test
    void exposesTypedPreviewContract() throws Exception {
        when(resolutionService.preview(eq(APPEAL), any(), any())).thenReturn(new ResolutionPreview(
                FinalOutcome.REVOKED, 5, 1, 2, "01ARZ3NDEKTSV4RRFFQ69G5AAC", null,
                "CLEAR", List.of(), List.of(), List.of("The enforcement will be revoked."),
                "01ARZ3NDEKTSV4RRFFQ69G5AAE", Instant.parse("2026-08-26T00:05:00Z")));

        mvc.perform(post("/api/v1/admin/appeals/{appealId}/resolution/dry-run", APPEAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedAppealVersion":5,"expectedEnforcementVersion":1,
                                 "expectedTargetVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REVOKED"))
                .andExpect(jsonPath("$.data.previewToken").value("01ARZ3NDEKTSV4RRFFQ69G5AAE"))
                .andExpect(jsonPath("$.data.predictedEffectiveEnforcementState").value("CLEAR"));
    }

    @Test
    void mapsStaleConfirmedResolutionToConflictEnvelope() throws Exception {
        when(resolutionService.resolve(eq(APPEAL), any(), any())).thenThrow(
                AppealException.conflict("APPEAL_RESOLUTION_PREVIEW_INVALID", "Preview again."));

        mvc.perform(post("/api/v1/admin/appeals/{appealId}/resolution", APPEAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedAppealVersion":5,"expectedEnforcementVersion":1,
                                 "expectedTargetVersion":2,
                                 "previewToken":"01ARZ3NDEKTSV4RRFFQ69G5AAE",
                                 "idempotencyKey":"resolution-key","confirmed":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("APPEAL_RESOLUTION_PREVIEW_INVALID"));
    }
}
