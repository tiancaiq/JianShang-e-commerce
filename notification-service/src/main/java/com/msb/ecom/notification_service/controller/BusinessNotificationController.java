package com.msb.ecom.notification_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.notification_service.dto.ApiDataResponse;
import com.msb.ecom.notification_service.dto.NotificationCountResponse;
import com.msb.ecom.notification_service.dto.NotificationPageResponse;
import com.msb.ecom.notification_service.service.NotificationReadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/notifications")
public class BusinessNotificationController {
    private final NotificationReadService service;

    public BusinessNotificationController(NotificationReadService service) { this.service = service; }

    @GetMapping
    public ApiDataResponse<NotificationPageResponse> list(
            @PathVariable String businessId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit,
            HttpServletRequest request) {
        return new ApiDataResponse<>(service.listBusiness(authorization,
                CorrelationIdFilter.current(request), businessId, cursor, limit));
    }

    @GetMapping("/unread-count")
    public ApiDataResponse<NotificationCountResponse> count(
            @PathVariable String businessId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        return new ApiDataResponse<>(new NotificationCountResponse(service.businessUnreadCount(
                authorization, CorrelationIdFilter.current(request), businessId)));
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable String businessId, @PathVariable String notificationId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        service.markBusinessRead(authorization, CorrelationIdFilter.current(request), businessId,
                notificationId, NotificationRequestBodyGuard.actualContentLength(request), null);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@PathVariable String businessId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        service.markAllBusinessRead(authorization, CorrelationIdFilter.current(request), businessId,
                NotificationRequestBodyGuard.actualContentLength(request), null);
    }
}
