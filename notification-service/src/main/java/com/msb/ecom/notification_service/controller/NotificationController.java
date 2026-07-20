package com.msb.ecom.notification_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.notification_service.dto.ApiDataResponse;
import com.msb.ecom.notification_service.dto.NotificationPageResponse;
import com.msb.ecom.notification_service.service.NotificationReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationReadService service;

    public NotificationController(NotificationReadService service) {
        this.service = service;
    }

    @GetMapping
    public ApiDataResponse<NotificationPageResponse> list(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit,
            HttpServletRequest request) {
        return new ApiDataResponse<>(service.list(
                authorization,
                CorrelationIdFilter.current(request),
                cursor,
                limit));
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @PathVariable String notificationId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        service.markRead(
                authorization,
                CorrelationIdFilter.current(request),
                notificationId,
                request.getContentLengthLong(),
                request.getHeader(HttpHeaders.TRANSFER_ENCODING));
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        service.markAllRead(
                authorization,
                CorrelationIdFilter.current(request),
                request.getContentLengthLong(),
                request.getHeader(HttpHeaders.TRANSFER_ENCODING));
    }
}
