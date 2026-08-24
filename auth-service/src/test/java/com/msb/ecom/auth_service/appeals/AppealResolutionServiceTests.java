package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AppealResolutionServiceTests {
    private static final String APPEAL = "01ARZ3NDEKTSV4RRFFQ69G5AAD";

    @Mock AppealService appeals;
    @Mock AppealResolutionFailureAuditService failureAudit;

    @Test
    void recordsFailureAfterTransactionalExecutorReturnsAnError() {
        ResolutionRequest request = new ResolutionRequest(5L, 1L, 2L,
                "01ARZ3NDEKTSV4RRFFQ69G5AAE", "resolution-key", true);
        AppealException conflict = AppealException.conflict("ENFORCEMENT_VERSION_CONFLICT",
                "Enforcement changed.");
        doThrow(new AppealResolutionAttemptException(conflict))
                .when(appeals).resolveTransaction(APPEAL, request, "corr");
        AppealResolutionService service = new AppealResolutionService(appeals, failureAudit);

        assertThatThrownBy(() -> service.resolve(APPEAL, request, "corr")).isSameAs(conflict);

        verify(failureAudit).record(APPEAL, "ENFORCEMENT_VERSION_CONFLICT", "corr");
    }

    @Test
    void permissionDenialBeforeExecutionDoesNotCreateAResolutionFailureAudit() {
        ResolutionRequest request = new ResolutionRequest(5L, 1L, 2L,
                "01ARZ3NDEKTSV4RRFFQ69G5AAE", "resolution-key", true);
        AppealException forbidden = AppealException.forbidden("APPEAL_RESOLUTION_PERMISSION_REQUIRED",
                "Appeal-resolution permission is required.");
        doThrow(forbidden).when(appeals).resolveTransaction(APPEAL, request, "corr");
        AppealResolutionService service = new AppealResolutionService(appeals, failureAudit);

        assertThatThrownBy(() -> service.resolve(APPEAL, request, "corr")).isSameAs(forbidden);

        verifyNoInteractions(failureAudit);
    }
}
