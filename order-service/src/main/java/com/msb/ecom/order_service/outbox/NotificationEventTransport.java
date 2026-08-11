package com.msb.ecom.order_service.outbox;

public interface NotificationEventTransport {
    void send(CommerceNotificationEvent event);
}
