package com.msb.ecom.order_service.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import static com.msb.ecom.order_service.analytics.OrderAnalyticsContracts.*;

@RestController
@RequestMapping("/api/v1/internal/admin/analytics")
public class InternalOrderAnalyticsController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";
    private final OrderAnalyticsService service;
    private final boolean configured;
    private final byte[] expectedDigest;

    public InternalOrderAnalyticsController(OrderAnalyticsService service,
            @Value("${commerce.internal-service-token:}") String token) {
        this.service = service;
        this.configured = token != null && !token.isBlank();
        this.expectedDigest = digest(token == null ? "" : token);
    }

    @GetMapping("/summary")
    public ResponseEntity<Summary> summary(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "UTC") String timezone,
            @RequestParam(defaultValue = "false") boolean compare,
            @RequestParam(defaultValue = "false") boolean includeFinancialAmounts) {
        authenticate(token);
        return ok(service.summary(from, to, timezone, compare, includeFinancialAmounts));
    }

    @GetMapping("/trends")
    public ResponseEntity<Trend> trend(
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String token,
            @RequestParam TrendMetric metric,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "UTC") String timezone,
            @RequestParam(defaultValue = "DAY") Granularity granularity) {
        authenticate(token);
        return ok(service.trend(metric, from, to, timezone, granularity));
    }

    private void authenticate(String supplied) {
        if (!configured || !MessageDigest.isEqual(expectedDigest, digest(supplied == null ? "" : supplied))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Internal order analytics authorization is required.");
        }
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
