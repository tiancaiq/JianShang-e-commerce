package com.msb.ecom.auth_service.support;

import org.springframework.http.HttpStatus;

public final class SupportException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private SupportException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public String code() { return code; }
    public HttpStatus status() { return status; }
    static SupportException invalid(String code, String message) { return new SupportException(HttpStatus.BAD_REQUEST, code, message); }
    static SupportException forbidden(String code, String message) { return new SupportException(HttpStatus.FORBIDDEN, code, message); }
    static SupportException notFound() { return new SupportException(HttpStatus.NOT_FOUND, "SUPPORT_TICKET_NOT_FOUND", "Support ticket was not found."); }
    static SupportException invalidLink() { return new SupportException(HttpStatus.NOT_FOUND, "SUPPORT_INVALID_LINK", "The linked marketplace record is unavailable."); }
    static SupportException conflict(String code, String message) { return new SupportException(HttpStatus.CONFLICT, code, message); }
    static SupportException unavailable() { return new SupportException(HttpStatus.SERVICE_UNAVAILABLE, "SUPPORT_CONTEXT_UNAVAILABLE", "Linked marketplace context is temporarily unavailable."); }
}
