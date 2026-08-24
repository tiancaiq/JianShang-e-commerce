package com.msb.ecom.auth_service.governance;

import java.util.Map;

interface GovernanceDomainExecutor {
    Validation validate(String actionType, String targetId, Map<String, Object> payload,
                        String executorAccessToken);

    Execution execute(String actionType, String targetId, Map<String, Object> payload,
                      String executorAccessToken, String idempotencyKey, String correlationId);

    record Validation(boolean valid, String fingerprintMaterial, String denialCode, String denialSummary) {
        static Validation valid(String fingerprintMaterial) {
            return new Validation(true, fingerprintMaterial, null, null);
        }

        static Validation invalid(String code, String summary) {
            return new Validation(false, null, code, summary);
        }
    }

    record Execution(boolean succeeded, String reference, String failureCode, String failureSummary) {
        static Execution succeeded(String reference) {
            return new Execution(true, reference, null, null);
        }

        static Execution failed(String code, String summary) {
            return new Execution(false, null, code, summary);
        }
    }
}
