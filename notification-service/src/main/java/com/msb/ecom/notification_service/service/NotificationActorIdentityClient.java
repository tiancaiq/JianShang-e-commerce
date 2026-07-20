package com.msb.ecom.notification_service.service;

public interface NotificationActorIdentityClient {

    String requireActiveUserId(String authorizationHeader, String correlationId);

    final class AuthenticationRequiredException extends RuntimeException {
    }

    final class AccessDeniedException extends RuntimeException {
    }

    final class DependencyUnavailableException extends RuntimeException {
    }
}
