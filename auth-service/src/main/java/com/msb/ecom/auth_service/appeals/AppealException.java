package com.msb.ecom.auth_service.appeals;

import org.springframework.http.HttpStatus;

final class AppealException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private AppealException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    HttpStatus status() { return status; }

    static AppealException invalid(String code, String message) {
        return new AppealException(code, HttpStatus.BAD_REQUEST, message);
    }
    static AppealException forbidden(String code, String message) {
        return new AppealException(code, HttpStatus.FORBIDDEN, message);
    }
    static AppealException notFound() {
        return new AppealException("APPEAL_NOT_FOUND", HttpStatus.NOT_FOUND, "Appeal was not found.");
    }
    static AppealException enforcementNotFound() {
        return new AppealException("ENFORCEMENT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Enforcement action was not found.");
    }
    static AppealException conflict(String code, String message) {
        return new AppealException(code, HttpStatus.CONFLICT, message);
    }
    static AppealException unavailable(String message) {
        return new AppealException("APPEAL_CONTEXT_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
