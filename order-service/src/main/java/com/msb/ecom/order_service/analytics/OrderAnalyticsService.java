package com.msb.ecom.order_service.analytics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.msb.ecom.order_service.analytics.OrderAnalyticsContracts.*;

@Service
public class OrderAnalyticsService {
    private final OrderAnalyticsRepository repository;
    private final Clock clock;

    @Autowired
    public OrderAnalyticsService(OrderAnalyticsRepository repository) {
        this(repository, Clock.systemUTC());
    }

    OrderAnalyticsService(OrderAnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Builds bounded aggregate-only order analytics without loading order or dispute rows. */
    public Summary summary(Instant from, Instant to, String timezone, boolean compare,
                           boolean includeFinancialAmounts) {
        OrderAnalyticsRange.Validated range = OrderAnalyticsRange.validate(from, to, timezone, compare);
        Instant generatedAt = clock.instant();
        Window current = window(range.from(), range.to(), includeFinancialAmounts);
        Window previous = compare
                ? window(range.previousFrom(), range.previousTo(), includeFinancialAmounts) : null;
        OrderAnalyticsRepository.BacklogRow backlog = repository.disputeBacklog();
        Instant oldest = backlog == null ? null : backlog.oldestOpenCreatedAt();
        Long age = oldest == null ? null : Math.max(0, Duration.between(oldest, generatedAt).toSeconds());
        DisputeBacklog currentBacklog = backlog == null ? new DisputeBacklog(0, 0, null, null)
                : new DisputeBacklog(backlog.openCount(), backlog.unassignedCount(), oldest, age);
        return new Summary(metadata(range), current, previous, currentBacklog, generatedAt);
    }

    public Trend trend(TrendMetric metric, Instant from, Instant to, String timezone,
                       Granularity granularity) {
        if (metric == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Trend metric is required.");
        }
        OrderAnalyticsRange.Validated range = OrderAnalyticsRange.validate(from, to, timezone, false);
        OrderAnalyticsRange.validateGranularity(range, granularity);
        List<TrendPoint> sparse = repository.trend(metric, granularity, from, to);
        return new Trend(metric, metadata(range), granularity,
                fill(range.from(), range.to(), granularity, sparse), clock.instant());
    }

    private Window window(Instant from, Instant to, boolean includeFinancialAmounts) {
        return new Window(repository.orders(from, to, includeFinancialAmounts), repository.disputes(from, to));
    }

    private AnalyticsRange metadata(OrderAnalyticsRange.Validated range) {
        return new AnalyticsRange(range.from(), range.to(), "UTC", range.previousFrom(), range.previousTo());
    }

    private List<TrendPoint> fill(Instant from, Instant to, Granularity granularity,
                                  List<TrendPoint> sparse) {
        Map<Instant, Long> values = new HashMap<>();
        sparse.forEach(point -> values.put(point.bucketStart(), point.value()));
        List<TrendPoint> result = new ArrayList<>();
        ZonedDateTime cursor = bucketStart(from, granularity);
        while (cursor.toInstant().isBefore(to)) {
            Instant start = cursor.toInstant();
            result.add(new TrendPoint(start, values.getOrDefault(start, 0L)));
            cursor = switch (granularity) {
                case HOUR -> cursor.plusHours(1);
                case DAY -> cursor.plusDays(1);
                case WEEK -> cursor.plusWeeks(1);
            };
        }
        return List.copyOf(result);
    }

    private ZonedDateTime bucketStart(Instant value, Granularity granularity) {
        ZonedDateTime utc = value.atZone(ZoneOffset.UTC);
        return switch (granularity) {
            case HOUR -> utc.truncatedTo(ChronoUnit.HOURS);
            case DAY -> utc.toLocalDate().atStartOfDay(ZoneOffset.UTC);
            case WEEK -> utc.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(ZoneOffset.UTC);
        };
    }
}
