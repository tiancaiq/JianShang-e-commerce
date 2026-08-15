package com.msb.ecom.notification_service.service;

public interface NotificationBusinessAccessClient {
    void requireNotificationAccess(String authorization, String correlationId, String businessId);
}
