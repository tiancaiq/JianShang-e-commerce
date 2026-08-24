package com.msb.ecom.auth_service.reporting;

import org.springframework.http.HttpStatus;

public final class ReportException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private ReportException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }

    public static ReportException invalid(String code, String message) { return new ReportException(code, HttpStatus.BAD_REQUEST, message); }
    public static ReportException notFound() { return new ReportException("REPORT_NOT_FOUND", HttpStatus.NOT_FOUND, "Report was not found."); }
    public static ReportException invalidTarget() { return new ReportException("REPORT_INVALID_TARGET", HttpStatus.NOT_FOUND, "The target is not available for reporting."); }
    public static ReportException conflict(String code, String message) { return new ReportException(code, HttpStatus.CONFLICT, message); }
    public static ReportException unavailable() { return new ReportException("REPORT_TARGET_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, "The report target is temporarily unavailable."); }
    public static ReportException rateLimited() { return new ReportException("REPORT_RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "Too many reports were submitted. Try again later."); }
}
