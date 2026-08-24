package com.msb.ecom.auth_service.appeals;

import com.msb.ecom.auth_service.appeals.AppealRepository.AppealRow;
import com.msb.ecom.auth_service.appeals.AppealRepository.EventRow;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
class AppealResolutionFailureAuditService {
    private final AppealRepository repository;
    private final AuthService authService;
    private final UlidGenerator ulids;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    // Records a failed finalization attempt only after the resolution transaction has rolled back.
    public void record(String rawAppealId, String failureCode, String correlationId) {
        String appealId;
        try {
            appealId = FixedLengthIds.requireTrimmed("Appeal ID", rawAppealId, 26);
        } catch (RuntimeException ignored) {
            return;
        }
        AppealRow appeal = repository.byId(appealId, false).orElse(null);
        if (appeal == null || appeal.status() == AppealContracts.Status.UPHELD
                || appeal.status() == AppealContracts.Status.MODIFIED
                || appeal.status() == AppealContracts.Status.REVOKED) {
            return;
        }
        User actor = authService.ensureUserEntity();
        Instant now = clock.instant();
        String safeCode = failureCode == null || failureCode.isBlank()
                ? "APPEAL_RESOLUTION_FAILED" : failureCode.substring(0, Math.min(64, failureCode.length()));
        repository.insertEvent(new EventRow(ulids.next(), appealId, "APPEAL_RESOLUTION_FAILED", now,
                "PLATFORM_ADMIN", actor.getId(), safeName(actor), "HUMAN_ADMIN",
                appeal.status().name(), appeal.status().name(), safeCode,
                "Appeal resolution did not complete.", correlation(correlationId), ulids.next(), Map.of()));
    }

    private String safeName(User actor) {
        return actor.getDisplayName() == null || actor.getDisplayName().isBlank()
                ? "Platform administrator" : actor.getDisplayName().trim();
    }

    private String correlation(String value) {
        return value == null || value.isBlank() ? ulids.next() : value;
    }
}
