package com.msb.ecom.payment_service.analytics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

import static com.msb.ecom.payment_service.analytics.PaymentAnalyticsContracts.*;

@Service
public class PaymentAnalyticsService {
    private final PaymentAnalyticsRepository repository;
    private final Clock clock;

    @Autowired
    public PaymentAnalyticsService(PaymentAnalyticsRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PaymentAnalyticsService(PaymentAnalyticsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Builds bounded aggregate-only payment analytics without exposing payment or actor identifiers. */
    public Summary summary(Instant from, Instant to, String timezone, boolean compare,
                           boolean includeFinancialAmounts) {
        PaymentAnalyticsRange.Validated range = PaymentAnalyticsRange.validate(from, to, timezone, compare);
        Instant generatedAt = clock.instant();
        Window current = window(range.from(), range.to(), includeFinancialAmounts);
        Window previous = compare
                ? window(range.previousFrom(), range.previousTo(), includeFinancialAmounts) : null;
        PaymentAnalyticsRepository.BacklogRow row = repository.refundBacklog(includeFinancialAmounts);
        Instant oldest = row == null ? null : row.oldestActiveCreatedAt();
        Long age = oldest == null ? null : Math.max(0, Duration.between(oldest, generatedAt).toSeconds());
        RefundBacklog backlog = row == null
                ? new RefundBacklog(0, 0, null, null, includeFinancialAmounts ? List.of() : null)
                : new RefundBacklog(row.pendingCount(), row.processingCount(), oldest, age, row.activeAmounts());
        return new Summary(metadata(range), current, previous, backlog, generatedAt);
    }

    public Trend trend(TrendMetric metric, Instant from, Instant to, String timezone,
                       Granularity granularity) {
        if (metric == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Trend metric is required.");
        }
        PaymentAnalyticsRange.Validated range = PaymentAnalyticsRange.validate(from, to, timezone, false);
        PaymentAnalyticsRange.validateGranularity(range, granularity);
        List<TrendPoint> sparse = repository.trend(metric, granularity, from, to);
        return new Trend(metric, metadata(range), granularity,
                fill(range.from(), range.to(), granularity, sparse), clock.instant());
    }

    private Window window(Instant from, Instant to, boolean includeFinancialAmounts) {
        return new Window(repository.payments(from, to, includeFinancialAmounts),
                repository.refunds(from, to, includeFinancialAmounts));
    }

    private AnalyticsRange metadata(PaymentAnalyticsRange.Validated range) {
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
