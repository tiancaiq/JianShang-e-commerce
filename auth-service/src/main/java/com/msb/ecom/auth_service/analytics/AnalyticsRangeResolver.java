package com.msb.ecom.auth_service.analytics;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.AnalyticsRange;
import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.Granularity;
import static com.msb.ecom.auth_service.analytics.AnalyticsContracts.RangePreset;

@Component
public class AnalyticsRangeResolver {
    static final Duration MAX_RANGE = Duration.ofDays(90);
    private final Clock clock;

    public AnalyticsRangeResolver(Clock clock) {
        this.clock = clock;
    }

    public AnalyticsRange resolve(String rawPreset, Instant rawFrom, Instant rawTo,
            String rawTimezone, boolean compare) {
        String timezone = rawTimezone == null || rawTimezone.isBlank()
                ? "UTC" : rawTimezone.trim().toUpperCase(Locale.ROOT);
        if (!"UTC".equals(timezone)) {
            throw new IllegalArgumentException("Analytics timezone must be UTC.");
        }
        RangePreset preset = preset(rawPreset, rawFrom, rawTo);
        Instant now = clock.instant();
        Instant from;
        Instant to;
        switch (preset) {
            case TODAY -> {
                from = now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS).toInstant();
                to = now;
            }
            case LAST_7_DAYS -> {
                from = now.minus(7, ChronoUnit.DAYS);
                to = now;
            }
            case LAST_30_DAYS -> {
                from = now.minus(30, ChronoUnit.DAYS);
                to = now;
            }
            case LAST_90_DAYS -> {
                from = now.minus(90, ChronoUnit.DAYS);
                to = now;
            }
            case CUSTOM -> {
                if (rawFrom == null || rawTo == null) {
                    throw new IllegalArgumentException("Custom analytics ranges require from and to.");
                }
                from = rawFrom;
                to = rawTo;
            }
            default -> throw new IllegalStateException("Unsupported analytics range.");
        }
        validate(from, to, now);
        Duration duration = Duration.between(from, to);
        return new AnalyticsRange(preset, from, to, timezone, compare,
                compare ? from.minus(duration) : null,
                compare ? from : null);
    }

    public void validateGranularity(AnalyticsRange range, Granularity granularity) {
        Duration duration = Duration.between(range.from(), range.to());
        if (granularity == Granularity.HOUR && duration.compareTo(Duration.ofHours(48)) > 0) {
            throw new IllegalArgumentException("Hourly analytics are limited to 48 hours.");
        }
        long points = switch (granularity) {
            case HOUR -> duration.toHours() + 1;
            case DAY -> duration.toDays() + 1;
            case WEEK -> duration.toDays() / 7 + 1;
        };
        if (points > 100) {
            throw new IllegalArgumentException("Analytics trends are limited to 100 points.");
        }
    }

    private RangePreset preset(String value, Instant rawFrom, Instant rawTo) {
        if (value == null || value.isBlank()) {
            if (rawFrom != null || rawTo != null) {
                return RangePreset.CUSTOM;
            }
            return RangePreset.LAST_30_DAYS;
        }
        try {
            return RangePreset.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Analytics range is invalid.");
        }
    }

    private void validate(Instant from, Instant to, Instant now) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("Analytics from must be before to.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("Analytics ranges are limited to 90 days.");
        }
        if (to.isAfter(now)) {
            throw new IllegalArgumentException("Analytics ranges cannot end in the future.");
        }
    }
}
