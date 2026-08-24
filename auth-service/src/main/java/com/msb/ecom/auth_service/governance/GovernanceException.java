package com.msb.ecom.auth_service.governance;

import org.springframework.http.HttpStatus;

public final class GovernanceException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    GovernanceException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    static GovernanceException invalid(String code, String message) {
        return new GovernanceException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    static GovernanceException forbidden(String code, String message) {
        return new GovernanceException(HttpStatus.FORBIDDEN, code, message);
    }

    static GovernanceException notFound(String message) {
        return new GovernanceException(HttpStatus.NOT_FOUND, "GOVERNANCE_NOT_FOUND", message);
    }

    static GovernanceException conflict(String code, String message) {
        return new GovernanceException(HttpStatus.CONFLICT, code, message);
    }

    static GovernanceException unavailable(String code, String message) {
        return new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
