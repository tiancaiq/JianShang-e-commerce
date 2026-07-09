package com.msb.ecom.chat_service.controller;

import com.msb.ecom.chat_service.model.ChatDependencyUnavailableException;
import com.msb.ecom.chat_service.model.ChatCompletionNotAllowedException;
import com.msb.ecom.chat_service.model.ChatConversationNotFoundException;
import com.msb.ecom.chat_service.model.ChatListingNotEligibleException;
import com.msb.ecom.chat_service.model.ChatMessageValidationException;
import com.msb.ecom.chat_service.model.SelfConversationNotAllowedException;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
@Slf4j
public class ChatExceptionHandler {

    @ExceptionHandler(ChatListingNotEligibleException.class)
    public ResponseEntity<ApiErrorEnvelope> handleListingNotEligible(
            ChatListingNotEligibleException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Rejected chat listing path={} correlationId={} reason={}",
                request.getRequestURI(), correlationId, exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("CHAT_LISTING_NOT_AVAILABLE", "Listing is not available for chat.", correlationId));
    }

    @ExceptionHandler(SelfConversationNotAllowedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleSelfConversation(
            SelfConversationNotAllowedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(
                        "CHAT_SELF_CONVERSATION_NOT_ALLOWED",
                        "You cannot start a buyer conversation with your own listing.",
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(ChatConversationNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleConversationNotFound(
            ChatConversationNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(
                        "CHAT_CONVERSATION_NOT_FOUND",
                        "Conversation was not found.",
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(ChatMessageValidationException.class)
    public ResponseEntity<ApiErrorEnvelope> handleMessageValidation(
            ChatMessageValidationException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error(
                        "CHAT_INVALID_MESSAGE",
                        exception.getMessage(),
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(ChatCompletionNotAllowedException.class)
    public ResponseEntity<ApiErrorEnvelope> handleCompletionNotAllowed(
            ChatCompletionNotAllowedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error(
                        "CHAT_COMPLETION_NOT_ALLOWED",
                        exception.getMessage(),
                        CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(ChatDependencyUnavailableException.class)
    public ResponseEntity<ApiErrorEnvelope> handleDependencyUnavailable(
            ChatDependencyUnavailableException exception,
            HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        log.warn("Chat dependency unavailable path={} correlationId={} reason={}",
                request.getRequestURI(), correlationId, exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("CHAT_DEPENDENCY_UNAVAILABLE", "Chat is temporarily unavailable.", correlationId));
    }

    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> handleAuthenticationRequired(
            AuthenticationCredentialsNotFoundException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error("AUTHENTICATION_REQUIRED", "Authentication is required.", CorrelationIdFilter.current(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorEnvelope> handleInvalidRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(error("CHAT_INVALID_REQUEST", exception.getMessage(), CorrelationIdFilter.current(request)));
    }

    private ApiErrorEnvelope error(String code, String message, String correlationId) {
        return new ApiErrorEnvelope(new ApiError(code, message, List.of(), correlationId));
    }
}
