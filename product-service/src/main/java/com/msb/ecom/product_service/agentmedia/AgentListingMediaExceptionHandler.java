package com.msb.ecom.product_service.agentmedia;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.error.ApiError;
import com.msb.ecom.common.web.error.ApiErrorEnvelope;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice(assignableTypes = AgentListingMediaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AgentListingMediaExceptionHandler {

    @ExceptionHandler(ListingAuthorizationException.class)
    public ResponseEntity<ApiErrorEnvelope> unauthorized(HttpServletRequest request) {
        return error(
                HttpStatus.FORBIDDEN,
                "AGENT_LISTING_MEDIA_FORBIDDEN",
                "Agent service authentication is required.",
                request);
    }

    @ExceptionHandler(AgentListingMediaNotFoundException.class)
    public ResponseEntity<ApiErrorEnvelope> notFound(HttpServletRequest request) {
        return error(
                HttpStatus.NOT_FOUND,
                "AGENT_LISTING_MEDIA_NOT_FOUND",
                "Owned listing draft media was not found.",
                request);
    }

    @ExceptionHandler(AgentListingMediaRejectedException.class)
    public ResponseEntity<ApiErrorEnvelope> rejected(HttpServletRequest request) {
        return error(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "AGENT_LISTING_MEDIA_REJECTED",
                "Selected listing media was rejected.",
                request);
    }

    @ExceptionHandler(AgentListingMediaUnavailableException.class)
    public ResponseEntity<ApiErrorEnvelope> unavailable(HttpServletRequest request) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AGENT_LISTING_MEDIA_UNAVAILABLE",
                "Listing media is temporarily unavailable.",
                request);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorEnvelope> invalid(HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                "AGENT_LISTING_MEDIA_INVALID_REQUEST",
                "The listing media request is invalid.",
                request);
    }

    private ResponseEntity<ApiErrorEnvelope> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(new ApiErrorEnvelope(new ApiError(
                        code,
                        message,
                        List.of(),
                        CorrelationIdFilter.current(request))));
    }
}
