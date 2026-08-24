package com.msb.ecom.order_service.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.msb.ecom.order_service.analytics.OrderAnalyticsContracts.*;

@Repository
public class OrderAnalyticsRepository {
    private static final String ACTIVE_DISPUTES = "'OPEN','WAITING_FOR_BUYER','WAITING_FOR_SELLER',"
            + "'UNDER_ADMIN_REVIEW','READY_FOR_DECISION'";

    private final JdbcTemplate jdbc;

    public OrderAnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Uses creation/confirmation event clocks while evaluating cancellation and delivery from current state. */
    public OrderWindow orders(Instant from, Instant to, boolean includeFinancialAmounts) {
        Counts counts = jdbc.queryForObject("""
                select count(*) orders_created,
                       coalesce(sum(o.status = 'CANCELLED'), 0) current_cancelled,
                       coalesce(sum(
                         exists(
                           select 1 from business_orders present
                           where present.order_id=o.id
                             and present.cancellation_status<>'CANCELLED'
                         )
                         and not exists(
                           select 1 from business_orders incomplete
                           where incomplete.order_id=o.id
                             and incomplete.cancellation_status<>'CANCELLED'
                             and incomplete.fulfillment_status<>'DELIVERED'
                         )), 0) fully_delivered
                from orders o
                where o.created_at >= ? and o.created_at < ?
                """, (rs, row) -> new Counts(rs.getLong("orders_created"),
                        rs.getLong("current_cancelled"), rs.getLong("fully_delivered")),
                timestamp(from), timestamp(to));
        Long confirmed = jdbc.queryForObject("""
                select count(*) from orders where confirmed_at >= ? and confirmed_at < ?
                """, Long.class, timestamp(from), timestamp(to));
        List<MoneyAmount> amounts = includeFinancialAmounts ? jdbc.query("""
                select currency, coalesce(sum(total), 0) amount
                from orders where created_at >= ? and created_at < ?
                group by currency order by currency
                """, (rs, row) -> new MoneyAmount(rs.getString("currency"), rs.getBigDecimal("amount")),
                timestamp(from), timestamp(to)) : null;
        Counts safe = counts == null ? new Counts(0, 0, 0) : counts;
        return new OrderWindow(safe.created(), confirmed == null ? 0 : confirmed,
                safe.cancelled(), safe.delivered(), amounts);
    }

    public DisputeWindow disputes(Instant from, Instant to) {
        Long opened = jdbc.queryForObject("""
                select count(*) from order_disputes where created_at >= ? and created_at < ?
                """, Long.class, timestamp(from), timestamp(to));
        ResolutionCounts resolved = jdbc.queryForObject("""
                select count(*) resolved,
                       coalesce(sum(status='RESOLVED_NO_ACTION'),0) no_action,
                       coalesce(sum(status='RETURN_APPROVED'),0) returns_approved,
                       coalesce(sum(status='REFUND_RECOMMENDED'),0) refunds_recommended,
                       coalesce(sum(status='PARTIAL_REFUND_RECOMMENDED'),0) partial_refunds_recommended
                from order_disputes where resolved_at >= ? and resolved_at < ?
                """, (rs, row) -> new ResolutionCounts(rs.getLong("resolved"), rs.getLong("no_action"),
                        rs.getLong("returns_approved"), rs.getLong("refunds_recommended"),
                        rs.getLong("partial_refunds_recommended")), timestamp(from), timestamp(to));
        ResolutionCounts safe = resolved == null ? new ResolutionCounts(0, 0, 0, 0, 0) : resolved;
        return new DisputeWindow(opened == null ? 0 : opened, safe.resolved(), safe.noAction(),
                safe.returnsApproved(), safe.refundsRecommended(), safe.partialRefundsRecommended());
    }

    public BacklogRow disputeBacklog() {
        return jdbc.queryForObject("""
                select count(*) open_count,
                       coalesce(sum(assigned_admin_id is null),0) unassigned_count,
                       min(created_at) oldest_open_created_at
                from order_disputes
                where status in (""" + ACTIVE_DISPUTES + ")", (rs, row) -> new BacklogRow(
                rs.getLong("open_count"), rs.getLong("unassigned_count"),
                instant(rs, "oldest_open_created_at")));
    }

    public List<TrendPoint> trend(TrendMetric metric, Granularity granularity, Instant from, Instant to) {
        String table = metric == TrendMetric.ORDERS_CREATED ? "orders" : "order_disputes";
        String bucket = bucket(granularity, "created_at");
        return jdbc.query("select " + bucket + " bucket_start, count(*) metric_value from " + table
                        + " where created_at >= ? and created_at < ?"
                        + " group by bucket_start order by bucket_start",
                (rs, row) -> new TrendPoint(rs.getTimestamp("bucket_start").toInstant(),
                        rs.getLong("metric_value")), timestamp(from), timestamp(to));
    }

    private String bucket(Granularity granularity, String column) {
        return switch (granularity) {
            case HOUR -> "timestamp(date_format(" + column + ", '%Y-%m-%d %H:00:00'))";
            case DAY -> "timestamp(date(" + column + "))";
            case WEEK -> "timestamp(date_sub(date(" + column + "), interval weekday(" + column + ") day))";
        };
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Counts(long created, long cancelled, long delivered) { }
    private record ResolutionCounts(long resolved, long noAction, long returnsApproved,
                                    long refundsRecommended, long partialRefundsRecommended) { }
    public record BacklogRow(long openCount, long unassignedCount, Instant oldestOpenCreatedAt) { }
}
