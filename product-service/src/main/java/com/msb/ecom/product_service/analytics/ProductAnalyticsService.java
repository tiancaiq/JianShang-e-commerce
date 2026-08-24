package com.msb.ecom.product_service.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.*;
import static com.msb.ecom.product_service.analytics.ProductAnalyticsRepository.*;

@Service
@RequiredArgsConstructor
public class ProductAnalyticsService {
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final Duration MAX_HOURLY_RANGE = Duration.ofHours(48);
    private static final int MAX_TREND_POINTS = 100;
    private static final String TIMEZONE = "UTC";

    private final ProductAnalyticsRepository repository;
    private final Clock clock;

    @Transactional(readOnly = true)
    // Produces bounded source-owned aggregates without copying or mutating listing state.
    public Summary summary(Instant from, Instant to) {
        AnalyticsRange range = range(from, to);
        Instant generatedAt = clock.instant();
        Map<String, Long> states = repository.listingStates();
        long total = states.values().stream().mapToLong(Long::longValue).sum();

        ModerationQueueRow queue = repository.moderationQueue();
        ModerationResolutionRow resolutions = repository.moderationResolutions(from, to);
        ModerationDecisionRow decisions = repository.moderationDecisions(from, to);

        List<BreakdownRow> createdActions = repository.enforcementCreatedByAction(from, to);
        List<BreakdownRow> activeActions = repository.enforcementActiveByAction(generatedAt);
        List<BreakdownRow> createdScopes = repository.enforcementCreatedByScope(from, to);
        List<BreakdownRow> activeScopes = repository.enforcementActiveByScope(generatedAt);

        return new Summary(
                range,
                new ListingStateSummary(total,
                        state(states, "DRAFT"), state(states, "PENDING_REVIEW"),
                        state(states, "ACTIVE"), state(states, "PAUSED"),
                        state(states, "SOLD"), state(states, "CLOSED"),
                        state(states, "REJECTED"), state(states, "CHANGES_REQUESTED"),
                        state(states, "REMOVED_BY_ADMIN"), repository.newListings(from, to)),
                new ModerationSummary(queue.open(), queue.unassigned(), queue.assigned(),
                        queue.oldestOpenAt(), age(queue.oldestOpenAt(), generatedAt),
                        resolutions.resolved(), resolutions.averageResolutionSeconds(),
                        decisions.resolved(), decisions.approved(), decisions.rejected(),
                        decisions.changesRequested(), rate(decisions.rejected(), decisions.resolved())),
                new CategorySummary(repository.topCategoriesByCurrentListings(),
                        repository.topCategoriesByNewListings(from, to)),
                new ListingEnforcementSummary("LISTING", total(createdActions), total(activeActions),
                        actions(createdActions, activeActions), scopes(createdScopes, activeScopes)),
                generatedAt);
    }

    @Transactional(readOnly = true)
    public ListingCreatedTrend listingCreatedTrend(
            Instant from, Instant to, Granularity granularity) {
        AnalyticsRange range = range(from, to);
        if (granularity == null) {
            throw invalid("Trend granularity is required.");
        }
        if (granularity == Granularity.HOUR && Duration.between(from, to).compareTo(MAX_HOURLY_RANGE) > 0) {
            throw invalid("Hourly trends support at most 48 hours.");
        }
        Map<Instant, Long> values = repository.listingCreatedBuckets(from, to, granularity);
        List<TrendPoint> points = points(from, to, granularity, values);
        if (points.size() > MAX_TREND_POINTS) {
            throw invalid("The requested trend would return too many points.");
        }
        return new ListingCreatedTrend("LISTINGS_CREATED", range, granularity, points,
                points.stream().mapToLong(TrendPoint::value).sum(), clock.instant());
    }

    private AnalyticsRange range(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw invalid("Analytics requires a non-empty half-open range with from before to.");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw invalid("Analytics ranges may not exceed 90 days.");
        }
        return new AnalyticsRange(from, to, TIMEZONE);
    }

    private List<TrendPoint> points(Instant from, Instant to, Granularity granularity,
            Map<Instant, Long> values) {
        List<TrendPoint> points = new ArrayList<>();
        Instant cursor = floor(from, granularity);
        while (cursor.isBefore(to)) {
            Instant next = advance(cursor, granularity);
            Instant pointFrom = cursor.isBefore(from) ? from : cursor;
            Instant pointTo = next.isAfter(to) ? to : next;
            points.add(new TrendPoint(pointFrom, pointTo, values.getOrDefault(cursor, 0L)));
            cursor = next;
        }
        return List.copyOf(points);
    }

    private Instant floor(Instant value, Granularity granularity) {
        ZonedDateTime utc = value.atZone(ZoneOffset.UTC);
        return switch (granularity) {
            case HOUR -> utc.withMinute(0).withSecond(0).withNano(0).toInstant();
            case DAY -> utc.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
            case WEEK -> utc.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
        };
    }

    private Instant advance(Instant value, Granularity granularity) {
        return switch (granularity) {
            case HOUR -> value.plus(Duration.ofHours(1));
            case DAY -> value.plus(Duration.ofDays(1));
            case WEEK -> value.plus(Duration.ofDays(7));
        };
    }

    private List<EnforcementActionBreakdown> actions(
            List<BreakdownRow> created, List<BreakdownRow> active) {
        Set<String> keys = keys(created, active);
        return keys.stream().map(key -> new EnforcementActionBreakdown(
                key, count(created, key), count(active, key))).toList();
    }

    private List<EnforcementScopeBreakdown> scopes(
            List<BreakdownRow> created, List<BreakdownRow> active) {
        Set<String> keys = keys(created, active);
        return keys.stream().map(key -> new EnforcementScopeBreakdown(
                key, count(created, key), count(active, key))).toList();
    }

    private Set<String> keys(List<BreakdownRow> first, List<BreakdownRow> second) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        first.stream().map(BreakdownRow::key).sorted().forEach(keys::add);
        second.stream().map(BreakdownRow::key).sorted().forEach(keys::add);
        return keys;
    }

    private long count(List<BreakdownRow> values, String key) {
        return values.stream().filter(value -> key.equals(value.key()))
                .mapToLong(BreakdownRow::count).sum();
    }

    private long total(List<BreakdownRow> values) {
        return values.stream().mapToLong(BreakdownRow::count).sum();
    }

    private long state(Map<String, Long> states, String status) {
        return states.getOrDefault(status, 0L);
    }

    private Long age(Instant oldest, Instant generatedAt) {
        return oldest == null ? null : Math.max(0, Duration.between(oldest, generatedAt).toSeconds());
    }

    private BigDecimal rate(long numerator, long denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
