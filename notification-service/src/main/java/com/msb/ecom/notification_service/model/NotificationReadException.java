package com.msb.ecom.notification_service.model;

import org.springframework.http.HttpStatus;

public final class NotificationReadException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public NotificationReadException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
