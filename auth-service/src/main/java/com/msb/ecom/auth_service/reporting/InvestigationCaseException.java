package com.msb.ecom.auth_service.reporting;

import org.springframework.http.HttpStatus;

final class InvestigationCaseException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private InvestigationCaseException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    String code() { return code; }
    HttpStatus status() { return status; }

    static InvestigationCaseException invalid(String code, String message) {
        return new InvestigationCaseException(code, HttpStatus.BAD_REQUEST, message);
    }

    static InvestigationCaseException notFound() {
        return new InvestigationCaseException("INVESTIGATION_CASE_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Investigation case was not found.");
    }

    static InvestigationCaseException conflict(String code, String message) {
        return new InvestigationCaseException(code, HttpStatus.CONFLICT, message);
    }

    static InvestigationCaseException forbidden(String code, String message) {
        return new InvestigationCaseException(code, HttpStatus.FORBIDDEN, message);
    }

    static InvestigationCaseException status(String code, HttpStatus status, String message) {
        return new InvestigationCaseException(code, status, message);
    }
}
