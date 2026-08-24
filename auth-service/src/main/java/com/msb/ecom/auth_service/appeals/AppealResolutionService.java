package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.auth_service.appeals.AppealContracts.Detail;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionPreview;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionPreviewRequest;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AppealResolutionService {
    private final AppealService appeals;
    private final AppealResolutionFailureAuditService failureAudit;

    public ResolutionPreview preview(String appealId, ResolutionPreviewRequest request, String correlationId) {
        return appeals.previewResolution(appealId, request, correlationId);
    }

    // Keeps failure auditing outside the rolled-back enforcement/appeal transaction.
    public Detail resolve(String appealId, ResolutionRequest request, String correlationId) {
        try {
            return appeals.resolveTransaction(appealId, request, correlationId);
        } catch (AppealResolutionAttemptException attempt) {
            RuntimeException original = attempt.original();
            String code = original instanceof AppealException appeal
                    ? appeal.code() : "APPEAL_RESOLUTION_UNEXPECTED_FAILURE";
            recordFailure(appealId, code, correlationId);
            throw original;
        }
    }

    private void recordFailure(String appealId, String code, String correlationId) {
        try {
            failureAudit.record(appealId, code, correlationId);
        } catch (RuntimeException auditFailure) {
            log.error("Appeal resolution failure audit could not be recorded appealId={} code={}",
                    appealId, code, auditFailure);
        }
    }
}
