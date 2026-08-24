package com.msb.ecom.order_service.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

final class OrderAnalyticsRange {
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final Duration MAX_HOURLY_RANGE = Duration.ofDays(2);

    private OrderAnalyticsRange() { }

    static Validated validate(Instant from, Instant to, String timezone, boolean compare) {
        if (from == null || to == null) {
            throw badRequest("Both from and to are required.");
        }
        if (!from.isBefore(to)) {
            throw badRequest("from must be earlier than to.");
        }
        Duration duration = Duration.between(from, to);
        if (duration.compareTo(MAX_RANGE) > 0) {
            throw badRequest("Analytics ranges cannot exceed 90 days.");
        }
        if (timezone == null || !"UTC".equalsIgnoreCase(timezone.trim())) {
            throw badRequest("Order analytics currently supports the UTC timezone only.");
        }
        Instant previousFrom = compare ? from.minus(duration) : null;
        Instant previousTo = compare ? from : null;
        return new Validated(from, to, previousFrom, previousTo);
    }

    static void validateGranularity(Validated range, OrderAnalyticsContracts.Granularity granularity) {
        if (granularity == null) {
            throw badRequest("Trend granularity is required.");
        }
        if (granularity == OrderAnalyticsContracts.Granularity.HOUR
                && Duration.between(range.from(), range.to()).compareTo(MAX_HOURLY_RANGE) > 0) {
            throw badRequest("Hourly analytics are limited to two days.");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record Validated(Instant from, Instant to, Instant previousFrom, Instant previousTo) { }
}
