package com.msb.ecom.auth_service.analytics;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.*;

@RestController
@RequestMapping("/api/v1/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {
    private final AdminAnalyticsService service;
    private final AnalyticsRangeResolver ranges;

    @GetMapping("/overview")
    public ResponseEntity<AdminAnalyticsOverview> overview(
            @RequestParam(name = "range", required = false) String preset,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "UTC") String timezone,
            @RequestParam(defaultValue = "true") boolean compare,
            HttpServletRequest request) {
        AnalyticsRange range = ranges.resolve(preset, from, to, timezone, compare);
        return ok(service.overview(range, CorrelationIdFilter.current(request)));
    }

    @GetMapping("/trends")
    public ResponseEntity<AnalyticsTrend> trend(
            @RequestParam TrendMetric metric,
            @RequestParam(name = "range", required = false) String preset,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "UTC") String timezone,
            @RequestParam(defaultValue = "DAY") Granularity granularity,
            HttpServletRequest request) {
        AnalyticsRange range = ranges.resolve(preset, from, to, timezone, false);
        ranges.validateGranularity(range, granularity);
        return ok(service.trend(range, metric, granularity, CorrelationIdFilter.current(request)));
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
