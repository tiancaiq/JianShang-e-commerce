package com.msb.ecom.payment_service.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.msb.ecom.payment_service.analytics.PaymentAnalyticsContracts.*;

@Repository
public class PaymentAnalyticsRepository {
    private final JdbcTemplate jdbc;

    public PaymentAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts distinct payment intents entering terminal states within the requested event window. */
    public PaymentWindow payments(Instant from, Instant to, boolean includeFinancialAmounts) {
        TerminalCounts counts = jdbc.queryForObject("""
                select count(distinct case when to_status='SUCCEEDED' then payment_intent_id end) succeeded,
                       count(distinct case when to_status='FAILED' then payment_intent_id end) failed
                from payment_status_history
                where to_status in ('SUCCEEDED','FAILED') and created_at >= ? and created_at < ?
                """, (rs, row) -> new TerminalCounts(rs.getLong("succeeded"), rs.getLong("failed")),
                timestamp(from), timestamp(to));
        TerminalCounts safe = counts == null ? new TerminalCounts(0, 0) : counts;
        long denominator = safe.succeeded() + safe.failed();
        BigDecimal rate = denominator == 0 ? null
                : BigDecimal.valueOf(safe.succeeded()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
        List<MoneyAmount> amounts = includeFinancialAmounts ? jdbc.query("""
                select p.currency, sum(p.amount) amount
                from payment_intents p
                join (
                    select distinct payment_intent_id from payment_status_history
                    where to_status='SUCCEEDED' and created_at >= ? and created_at < ?
                ) succeeded on succeeded.payment_intent_id=p.id
                group by p.currency order by p.currency
                """, (rs, row) -> new MoneyAmount(rs.getString("currency"), rs.getBigDecimal("amount")),
                timestamp(from), timestamp(to)) : null;
        return new PaymentWindow(safe.succeeded(), safe.failed(), rate, amounts);
    }

    /** Keeps return refunds as successful outcomes without fabricating request, type, or failure states. */
    public RefundWindow refunds(Instant from, Instant to, boolean includeFinancialAmounts) {
        RequestedCounts requested = jdbc.queryForObject("""
                select count(*) requested,
                       coalesce(sum(refund_type='FULL'),0) full_requested,
                       coalesce(sum(refund_type='PARTIAL'),0) partial_requested
                from payment_refunds where created_at >= ? and created_at < ?
                """, (rs, row) -> new RequestedCounts(rs.getLong("requested"),
                        rs.getLong("full_requested"), rs.getLong("partial_requested")),
                timestamp(from), timestamp(to));
        OutcomeCounts outcomes = jdbc.queryForObject("""
                select count(distinct case when h.to_status='SUCCEEDED' then h.refund_id end) succeeded,
                       count(distinct case when h.to_status='FAILED' then h.refund_id end) failed,
                       count(distinct case when h.to_status='SUCCEEDED' and r.refund_type='FULL' then h.refund_id end) full_succeeded,
                       count(distinct case when h.to_status='SUCCEEDED' and r.refund_type='PARTIAL' then h.refund_id end) partial_succeeded,
                       count(distinct case when h.to_status='FAILED' and r.refund_type='FULL' then h.refund_id end) full_failed,
                       count(distinct case when h.to_status='FAILED' and r.refund_type='PARTIAL' then h.refund_id end) partial_failed
                from payment_refund_status_history h
                join payment_refunds r on r.id=h.refund_id
                where h.to_status in ('SUCCEEDED','FAILED') and h.created_at >= ? and h.created_at < ?
                """, (rs, row) -> new OutcomeCounts(rs.getLong("succeeded"), rs.getLong("failed"),
                        rs.getLong("full_succeeded"), rs.getLong("partial_succeeded"),
                        rs.getLong("full_failed"), rs.getLong("partial_failed")),
                timestamp(from), timestamp(to));
        Long returnSucceeded = jdbc.queryForObject("""
                select count(*) from payment_return_refunds
                where completed_at >= ? and completed_at < ?
                """, Long.class, timestamp(from), timestamp(to));
        RequestedCounts safeRequested = requested == null ? new RequestedCounts(0, 0, 0) : requested;
        OutcomeCounts safeOutcomes = outcomes == null ? new OutcomeCounts(0, 0, 0, 0, 0, 0) : outcomes;
        long safeReturns = returnSucceeded == null ? 0 : returnSucceeded;
        List<RefundAmountMetrics> amounts = includeFinancialAmounts
                ? refundAmountMetrics(from, to) : null;
        return new RefundWindow(safeRequested.requested(), safeOutcomes.succeeded() + safeReturns,
                safeOutcomes.failed(), safeRequested.fullRequested(), safeRequested.partialRequested(),
                safeOutcomes.fullSucceeded(), safeOutcomes.partialSucceeded(), safeOutcomes.fullFailed(),
                safeOutcomes.partialFailed(), safeReturns, amounts);
    }

    public BacklogRow refundBacklog(boolean includeFinancialAmounts) {
        BacklogCounts counts = jdbc.queryForObject("""
                select coalesce(sum(status='PENDING'),0) pending_count,
                       coalesce(sum(status='PROCESSING'),0) processing_count,
                       min(created_at) oldest_active_created_at
                from payment_refunds where status in ('PENDING','PROCESSING')
                """, (rs, row) -> new BacklogCounts(rs.getLong("pending_count"),
                        rs.getLong("processing_count"), instant(rs, "oldest_active_created_at")));
        List<MoneyAmount> amounts = includeFinancialAmounts ? jdbc.query("""
                select currency, sum(amount) amount from payment_refunds
                where status in ('PENDING','PROCESSING') group by currency order by currency
                """, (rs, row) -> new MoneyAmount(rs.getString("currency"), rs.getBigDecimal("amount"))) : null;
        BacklogCounts safe = counts == null ? new BacklogCounts(0, 0, null) : counts;
        return new BacklogRow(safe.pending(), safe.processing(), safe.oldest(), amounts);
    }

    public List<TrendPoint> trend(TrendMetric metric, Granularity granularity, Instant from, Instant to) {
        String bucket = bucket(granularity, "created_at");
        if (metric == TrendMetric.PAYMENTS_SUCCEEDED) {
            return jdbc.query("select " + bucket + " bucket_start, count(distinct payment_intent_id) metric_value"
                            + " from payment_status_history where to_status='SUCCEEDED'"
                            + " and created_at >= ? and created_at < ? group by bucket_start order by bucket_start",
                    (rs, row) -> point(rs), timestamp(from), timestamp(to));
        }
        Map<Instant, Long> totals = new TreeMap<>();
        jdbc.query("select " + bucket + " bucket_start, count(distinct refund_id) metric_value"
                        + " from payment_refund_status_history where to_status='SUCCEEDED'"
                        + " and created_at >= ? and created_at < ? group by bucket_start order by bucket_start",
                (rs, row) -> point(rs), timestamp(from), timestamp(to))
                .forEach(point -> totals.merge(point.bucketStart(), point.value(), Long::sum));
        String returnBucket = bucket(granularity, "completed_at");
        jdbc.query("select " + returnBucket + " bucket_start, count(*) metric_value"
                        + " from payment_return_refunds where completed_at >= ? and completed_at < ?"
                        + " group by bucket_start order by bucket_start",
                (rs, row) -> point(rs), timestamp(from), timestamp(to))
                .forEach(point -> totals.merge(point.bucketStart(), point.value(), Long::sum));
        return totals.entrySet().stream().map(entry -> new TrendPoint(entry.getKey(), entry.getValue())).toList();
    }

    private List<RefundAmountMetrics> refundAmountMetrics(Instant from, Instant to) {
        Map<String, MutableRefundAmounts> totals = new TreeMap<>();
        jdbc.query("""
                select currency, sum(amount) amount from payment_refunds
                where created_at >= ? and created_at < ? group by currency
                """, (rs, row) -> new AmountRow(rs.getString("currency"), rs.getBigDecimal("amount")),
                timestamp(from), timestamp(to)).forEach(row -> totals.computeIfAbsent(row.currency(), ignored ->
                new MutableRefundAmounts()).requested = row.amount());
        jdbc.query("""
                select r.currency, sum(r.amount) amount from payment_refunds r
                join (select distinct refund_id from payment_refund_status_history
                      where to_status='SUCCEEDED' and created_at >= ? and created_at < ?) h on h.refund_id=r.id
                group by r.currency
                """, (rs, row) -> new AmountRow(rs.getString("currency"), rs.getBigDecimal("amount")),
                timestamp(from), timestamp(to)).forEach(row -> totals.computeIfAbsent(row.currency(), ignored ->
                new MutableRefundAmounts()).succeeded = row.amount());
        jdbc.query("""
                select currency, sum(amount) amount from payment_return_refunds
                where completed_at >= ? and completed_at < ? group by currency
                """, (rs, row) -> new AmountRow(rs.getString("currency"), rs.getBigDecimal("amount")),
                timestamp(from), timestamp(to)).forEach(row -> {
                    MutableRefundAmounts value = totals.computeIfAbsent(row.currency(), ignored ->
                            new MutableRefundAmounts());
                    value.succeeded = value.succeeded.add(row.amount());
                });
        jdbc.query("""
                select r.currency, sum(r.amount) amount from payment_refunds r
                join (select distinct refund_id from payment_refund_status_history
                      where to_status='FAILED' and created_at >= ? and created_at < ?) h on h.refund_id=r.id
                group by r.currency
                """, (rs, row) -> new AmountRow(rs.getString("currency"), rs.getBigDecimal("amount")),
                timestamp(from), timestamp(to)).forEach(row -> totals.computeIfAbsent(row.currency(), ignored ->
                new MutableRefundAmounts()).failed = row.amount());
        List<RefundAmountMetrics> result = new ArrayList<>();
        totals.forEach((currency, value) -> result.add(new RefundAmountMetrics(currency,
                value.requested, value.succeeded, value.failed)));
        return List.copyOf(result);
    }

    private String bucket(Granularity granularity, String column) {
        return switch (granularity) {
            case HOUR -> "timestamp(date_format(" + column + ", '%Y-%m-%d %H:00:00'))";
            case DAY -> "timestamp(date(" + column + "))";
            case WEEK -> "timestamp(date_sub(date(" + column + "), interval weekday(" + column + ") day))";
        };
    }

    private static TrendPoint point(ResultSet rs) throws SQLException {
        return new TrendPoint(rs.getTimestamp("bucket_start").toInstant(), rs.getLong("metric_value"));
    }
    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record TerminalCounts(long succeeded, long failed) { }
    private record RequestedCounts(long requested, long fullRequested, long partialRequested) { }
    private record OutcomeCounts(long succeeded, long failed, long fullSucceeded,
                                 long partialSucceeded, long fullFailed, long partialFailed) { }
    private record BacklogCounts(long pending, long processing, Instant oldest) { }
    private record AmountRow(String currency, BigDecimal amount) { }
    public record BacklogRow(long pendingCount, long processingCount, Instant oldestActiveCreatedAt,
                             List<MoneyAmount> activeAmounts) { }

    private static final class MutableRefundAmounts {
        private BigDecimal requested = BigDecimal.ZERO;
        private BigDecimal succeeded = BigDecimal.ZERO;
        private BigDecimal failed = BigDecimal.ZERO;
    }
}
