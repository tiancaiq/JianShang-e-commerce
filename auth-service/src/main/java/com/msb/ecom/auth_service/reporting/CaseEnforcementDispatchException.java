package com.msb.ecom.auth_service.reporting;

final class CaseEnforcementDispatchException extends RuntimeException {
    private final String code;
    private final int status;

    CaseEnforcementDispatchException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    int status() { return status; }
}
