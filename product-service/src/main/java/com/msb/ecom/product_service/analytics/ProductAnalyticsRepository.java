package com.msb.ecom.product_service.analytics;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.CategoryCount;
import static com.msb.ecom.product_service.analytics.ProductAnalyticsContracts.Granularity;

@Repository
@RequiredArgsConstructor
public class ProductAnalyticsRepository {
    private static final int TOP_CATEGORY_LIMIT = 10;

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Long> listingStates() {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("select status, count(*) listing_count from listings group by status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getLong("listing_count"));
                });
        return Map.copyOf(counts);
    }

    public long newListings(Instant from, Instant to) {
        Long value = jdbcTemplate.queryForObject("""
                select count(*)
                from listings
                where created_at >= ? and created_at < ?
                """, Long.class, Timestamp.from(from), Timestamp.from(to));
        return value == null ? 0 : value;
    }

    public ModerationQueueRow moderationQueue() {
        return jdbcTemplate.queryForObject("""
                select count(*) open_count,
                       coalesce(sum(case when assigned_admin_user_id is null then 1 else 0 end), 0) unassigned_count,
                       coalesce(sum(case when assigned_admin_user_id is not null then 1 else 0 end), 0) assigned_count,
                       min(created_at) oldest_open_at
                from moderation_cases
                where case_type = 'LISTING_REVIEW'
                  and status in ('OPEN', 'CLAIMED')
                """, (rs, rowNum) -> new ModerationQueueRow(
                rs.getLong("open_count"),
                rs.getLong("unassigned_count"),
                rs.getLong("assigned_count"),
                instant(rs.getTimestamp("oldest_open_at"))));
    }

    public ModerationResolutionRow moderationResolutions(Instant from, Instant to) {
        return jdbcTemplate.queryForObject("""
                select count(*) resolved_count,
                       round(avg(timestampdiff(microsecond, created_at, resolved_at)) / 1000000) average_seconds
                from moderation_cases
                where case_type = 'LISTING_REVIEW'
                  and status = 'RESOLVED'
                  and resolved_at >= ? and resolved_at < ?
                """, (rs, rowNum) -> new ModerationResolutionRow(
                rs.getLong("resolved_count"), number(rs.getObject("average_seconds"))),
                Timestamp.from(from), Timestamp.from(to));
    }

    public ModerationDecisionRow moderationDecisions(Instant from, Instant to) {
        return jdbcTemplate.queryForObject("""
                select count(*) resolved_decisions,
                       coalesce(sum(case when decision = 'APPROVE' then 1 else 0 end), 0) approved_count,
                       coalesce(sum(case when decision = 'REJECT' then 1 else 0 end), 0) rejected_count,
                       coalesce(sum(case when decision = 'REQUEST_CHANGES' then 1 else 0 end), 0) changes_requested_count
                from listing_moderation_decisions
                where created_at >= ? and created_at < ?
                  and decision in ('APPROVE', 'REJECT', 'REQUEST_CHANGES')
                """, (rs, rowNum) -> new ModerationDecisionRow(
                rs.getLong("resolved_decisions"),
                rs.getLong("approved_count"),
                rs.getLong("rejected_count"),
                rs.getLong("changes_requested_count")),
                Timestamp.from(from), Timestamp.from(to));
    }

    public List<CategoryCount> topCategoriesByCurrentListings() {
        return jdbcTemplate.query("""
                select category.id category_id, category.name category_name, count(*) listing_count
                from listings listing
                join categories category on category.id = listing.category_id
                group by category.id, category.name
                order by listing_count desc, category.id
                limit ?
                """, (rs, rowNum) -> new CategoryCount(
                rs.getString("category_id"), rs.getString("category_name"),
                rs.getLong("listing_count")), TOP_CATEGORY_LIMIT);
    }

    public List<CategoryCount> topCategoriesByNewListings(Instant from, Instant to) {
        return jdbcTemplate.query("""
                select category.id category_id, category.name category_name, count(*) listing_count
                from listings listing
                join categories category on category.id = listing.category_id
                where listing.created_at >= ? and listing.created_at < ?
                group by category.id, category.name
                order by listing_count desc, category.id
                limit ?
                """, (rs, rowNum) -> new CategoryCount(
                rs.getString("category_id"), rs.getString("category_name"),
                rs.getLong("listing_count")), Timestamp.from(from), Timestamp.from(to), TOP_CATEGORY_LIMIT);
    }

    public List<BreakdownRow> enforcementCreatedByAction(Instant from, Instant to) {
        return breakdown("""
                select action_type breakdown_key, count(*) breakdown_count
                from enforcement_actions
                where created_at >= ? and created_at < ?
                group by action_type
                order by action_type
                """, Timestamp.from(from), Timestamp.from(to));
    }

    public List<BreakdownRow> enforcementActiveByAction(Instant now) {
        return breakdown("""
                select action_type breakdown_key, count(*) breakdown_count
                from enforcement_actions
                where revoked_at is null
                  and effective_at <= ?
                  and (expires_at is null or expires_at > ?)
                group by action_type
                order by action_type
                """, Timestamp.from(now), Timestamp.from(now));
    }

    public List<BreakdownRow> enforcementCreatedByScope(Instant from, Instant to) {
        return breakdown("""
                select scope.scope breakdown_key, count(*) breakdown_count
                from enforcement_actions action
                join enforcement_action_scopes scope on scope.enforcement_action_id = action.id
                where action.created_at >= ? and action.created_at < ?
                group by scope.scope
                order by scope.scope
                """, Timestamp.from(from), Timestamp.from(to));
    }

    public List<BreakdownRow> enforcementActiveByScope(Instant now) {
        return breakdown("""
                select scope.scope breakdown_key, count(*) breakdown_count
                from enforcement_actions action
                join enforcement_action_scopes scope on scope.enforcement_action_id = action.id
                where action.revoked_at is null
                  and action.effective_at <= ?
                  and (action.expires_at is null or action.expires_at > ?)
                group by scope.scope
                order by scope.scope
                """, Timestamp.from(now), Timestamp.from(now));
    }

    public Map<Instant, Long> listingCreatedBuckets(Instant from, Instant to, Granularity granularity) {
        String bucket = switch (granularity) {
            case HOUR -> "timestamp(date_format(created_at, '%Y-%m-%d %H:00:00'))";
            case DAY -> "timestamp(date(created_at))";
            case WEEK -> "timestamp(date_sub(date(created_at), interval weekday(created_at) day))";
        };
        String sql = """
                select %s bucket_start, count(*) bucket_count
                from listings
                where created_at >= ? and created_at < ?
                group by %s
                order by bucket_start
                """.formatted(bucket, bucket);
        LinkedHashMap<Instant, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
                    result.put(rs.getTimestamp("bucket_start").toInstant(), rs.getLong("bucket_count"));
                },
                Timestamp.from(from), Timestamp.from(to));
        return Map.copyOf(result);
    }

    private List<BreakdownRow> breakdown(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> new BreakdownRow(
                rs.getString("breakdown_key"), rs.getLong("breakdown_count")), arguments);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    public record ModerationQueueRow(long open, long unassigned, long assigned, Instant oldestOpenAt) { }
    public record ModerationResolutionRow(long resolved, Long averageResolutionSeconds) { }
    public record ModerationDecisionRow(long resolved, long approved, long rejected, long changesRequested) { }
    public record BreakdownRow(String key, long count) { }
}
