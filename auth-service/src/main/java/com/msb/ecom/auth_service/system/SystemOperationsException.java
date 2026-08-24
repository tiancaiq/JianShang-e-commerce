package com.msb.ecom.auth_service.system;

import org.springframework.http.HttpStatus;

public final class SystemOperationsException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public SystemOperationsException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static SystemOperationsException notFound() {
        return new SystemOperationsException(HttpStatus.NOT_FOUND, "SYSTEM_TARGET_NOT_FOUND",
                "The operational target is unavailable or no longer exists.");
    }

    public static SystemOperationsException conflict(String code, String message) {
        return new SystemOperationsException(HttpStatus.CONFLICT, code, message);
    }

    public static SystemOperationsException invalid(String code, String message) {
        return new SystemOperationsException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static SystemOperationsException unavailable() {
        return new SystemOperationsException(HttpStatus.SERVICE_UNAVAILABLE,
                "SYSTEM_DEPENDENCY_UNAVAILABLE",
                "The owning service is unavailable. No recovery command was requested.");
    }
}
