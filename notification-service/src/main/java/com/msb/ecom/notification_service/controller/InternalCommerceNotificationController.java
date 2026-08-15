package com.msb.ecom.notification_service.controller;

import com.msb.ecom.notification_service.model.CommerceNotificationEvent;
import com.msb.ecom.notification_service.model.NotificationConsumeResult;
import com.msb.ecom.notification_service.service.CommerceNotificationConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/notification-events")
public class InternalCommerceNotificationController {
    private final CommerceNotificationConsumer consumer;
    private final String token;

    public InternalCommerceNotificationController(
            CommerceNotificationConsumer consumer,
            @Value("${notifications.commerce-events.internal-service-token:}") String token) {
        this.consumer = consumer;
        this.token = token;
    }

    @PostMapping
    public ResponseEntity<NotificationConsumeResult> consume(
            @RequestHeader(name = "X-Internal-Service-Token", required = false) String supplied,
            @RequestBody CommerceNotificationEvent event) {
        if (token.isBlank() || supplied == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        NotificationConsumeResult result = consumer.consume(event);
        return switch (result.outcome()) {
            case CREATED, REPLAYED -> ResponseEntity.accepted().body(result);
            case CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(result);
            case POISON, REJECTED -> ResponseEntity.unprocessableEntity().body(result);
            case RETRY_REQUIRED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result);
            case DISABLED -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
        };
    }
}
