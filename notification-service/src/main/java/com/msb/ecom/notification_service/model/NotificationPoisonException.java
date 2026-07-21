package com.msb.ecom.notification_service.model;

public final class NotificationPoisonException extends RuntimeException {

    public NotificationPoisonException() {
        super("Notification event is invalid.");
    }
}
