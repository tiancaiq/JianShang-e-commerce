package com.msb.ecom.product_service.operations;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.msb.ecom.product_service.operations.ProductOperationsContracts.*;

@Repository
@RequiredArgsConstructor
public class ProductOperationsRepository {
    private final JdbcTemplate jdbc;

    public List<Job> jobs(Instant now, int projectionMax, int vectorMax) {
        List<Job> projection = jdbc.query("""
                select work_id, listing_id, state, attempt_count, next_attempt_at,
                       claim_token, claim_expires_at, last_error_code, correlation_id,
                       completed_at, created_at
                from listing_search_projection_work
                where state <> 'APPLIED' or attempt_count > 0
                order by created_at desc limit 100
                """, (rs, rowNum) -> job(rs.getString("work_id"), "LISTING_SEARCH_PROJECTION",
                        rs.getString("listing_id"), rs.getString("state"),
                        rs.getInt("attempt_count"), projectionMax,
                        instant(rs.getTimestamp("next_attempt_at")), rs.getString("claim_token"),
                        instant(rs.getTimestamp("claim_expires_at")), rs.getString("last_error_code"),
                        rs.getString("correlation_id"), instant(rs.getTimestamp("completed_at")),
                        rs.getTimestamp("created_at").toInstant(), now));
        List<Job> vector = jdbc.query("""
                select work_id, listing_id, state, attempt_count, next_attempt_at,
                       claim_token, claim_expires_at, last_error_code, completed_at, created_at
                from listing_search_vector_apply_work
                where state <> 'APPLIED' or attempt_count > 0
                order by created_at desc limit 100
                """, (rs, rowNum) -> job(rs.getString("work_id"), "LISTING_VECTOR_APPLY",
                        rs.getString("listing_id"), rs.getString("state"),
                        rs.getInt("attempt_count"), vectorMax,
                        instant(rs.getTimestamp("next_attempt_at")), rs.getString("claim_token"),
                        instant(rs.getTimestamp("claim_expires_at")), rs.getString("last_error_code"),
                        null, instant(rs.getTimestamp("completed_at")),
                        rs.getTimestamp("created_at").toInstant(), now));
        java.util.ArrayList<Job> combined = new java.util.ArrayList<>(projection);
        combined.addAll(vector);
        return combined.stream().sorted(java.util.Comparator.comparing(Job::createdAt).reversed()).toList();
    }

    public List<Outbox> outbox(Instant now) {
        return jdbc.query("""
                select event_id, event_type, aggregate_type, aggregate_id, published_at,
                       retry_count, next_attempt_at, claim_token, claim_expires_at,
                       last_error_code, correlation_id, created_at
                from outbox_events
                where published_at is null or retry_count > 0
                order by created_at desc limit 100
                """, (rs, rowNum) -> {
            Instant published = instant(rs.getTimestamp("published_at"));
            int attempts = rs.getInt("retry_count");
            Instant claimExpiry = instant(rs.getTimestamp("claim_expires_at"));
            boolean claimed = rs.getString("claim_token") != null && claimExpiry != null && claimExpiry.isAfter(now);
            String status = published != null ? "SENT" : attempts > 0 ? "FAILED" : "PENDING";
            return new Outbox(rs.getString("event_id"), "PRODUCT", rs.getString("event_type"),
                    rs.getString("aggregate_type"), rs.getString("aggregate_id"), status, attempts,
                    rs.getTimestamp("created_at").toInstant(), null,
                    instant(rs.getTimestamp("next_attempt_at")), rs.getString("correlation_id"),
                    rs.getString("last_error_code"), summary(rs.getString("last_error_code")),
                    published == null && attempts > 0 && !claimed);
        });
    }

    public Optional<Job> job(String id, Instant now, int projectionMax, int vectorMax) {
        return jobs(now, projectionMax, vectorMax).stream().filter(value -> id.equals(value.jobId())).findFirst();
    }

    public Optional<Outbox> outbox(String id, Instant now) {
        return outbox(now).stream().filter(value -> id.equals(value.eventId())).findFirst();
    }

    public boolean retryJob(String id, Instant now) {
        int projection = jdbc.update("""
                update listing_search_projection_work
                set next_attempt_at = ?, claim_token = null, claim_expires_at = null
                where work_id = ? and state = 'PENDING' and attempt_count > 0
                  and (claim_token is null or claim_expires_at <= ?)
                """, Timestamp.from(now), id, Timestamp.from(now));
        if (projection == 1) return true;
        return jdbc.update("""
                update listing_search_vector_apply_work
                set next_attempt_at = ?, claim_token = null, claim_expires_at = null, updated_at = ?
                where work_id = ? and state = 'PENDING' and attempt_count > 0
                  and (claim_token is null or claim_expires_at <= ?)
                """, Timestamp.from(now), Timestamp.from(now), id, Timestamp.from(now)) == 1;
    }

    public boolean retryOutbox(String id, Instant now) {
        return jdbc.update("""
                update outbox_events
                set next_attempt_at = ?, claim_token = null, claim_expires_at = null
                where event_id = ? and published_at is null and retry_count > 0
                  and (claim_token is null or claim_expires_at <= ?)
                """, Timestamp.from(now), id, Timestamp.from(now)) == 1;
    }

    public Optional<ListingState> listing(String id) {
        return listing(id, false);
    }

    public Optional<ListingState> listingForUpdate(String id) {
        return listing(id, true);
    }

    private Optional<ListingState> listing(String id, boolean forUpdate) {
        return jdbc.query("""
                select id, version, seller_type, status, moderation_status, publication_source
                from listings where id = ?%s
                """.formatted(forUpdate ? " for update" : ""),
                (rs, rowNum) -> new ListingState(rs.getString("id"), rs.getLong("version"),
                        rs.getString("seller_type"), rs.getString("status"),
                        rs.getString("moderation_status"), rs.getString("publication_source")), id)
                .stream().findFirst();
    }

    public void reindex(ListingState listing, String workId, String correlation, Instant now) {
        jdbc.update("""
                insert into listing_search_projection_work (
                    work_id, listing_id, listing_version, replay_sequence, operation, state, attempt_count,
                    next_attempt_at, claim_token, claim_expires_at, last_error_code,
                    correlation_id, completed_at, created_at
                ) values (?, ?, ?, (
                    select coalesce(max(existing.replay_sequence), 0) + 1
                    from listing_search_projection_work existing
                    where existing.listing_id = ? and existing.listing_version = ?
                ), ?, 'PENDING', 0, ?, null, null, null, ?, null, ?)
                """, workId, listing.id(), listing.version(), listing.id(), listing.version(),
                listing.publiclySearchable() ? "UPSERT" : "DELETE",
                Timestamp.from(now), correlation, Timestamp.from(now));
    }

    public SearchStatus searchStatus(List<Job> jobs) {
        List<Job> searchJobs = jobs.stream().filter(value -> value.jobType().startsWith("LISTING_")).toList();
        long pending = searchJobs.stream().filter(value -> "PENDING".equals(value.status())).count();
        long failed = searchJobs.stream().filter(value -> "FAILED".equals(value.status())
                || "DEAD_LETTER".equals(value.status())).count();
        Timestamp lastCompleted = jdbc.queryForObject("""
                select max(completed_at) from listing_search_projection_work where state = 'APPLIED'
                """, Timestamp.class);
        Instant lastSuccess = lastCompleted == null ? null : lastCompleted.toInstant();
        List<SearchIssue> issues = searchJobs.stream().filter(value -> !"SUCCEEDED".equals(value.status()))
                .map(value -> new SearchIssue(value.jobId(), "LISTING", value.relatedTargetId(),
                        value.jobType(), value.status(), value.attemptCount(), value.lastAttemptAt(),
                        value.safeFailureSummary(), value.retryable())).toList();
        String status = failed > 0 ? "DEGRADED" : "HEALTHY";
        return new SearchStatus("PRODUCT", status, pending, failed, lastSuccess,
                "marketplace-public-listing-v1", failed > 0
                ? "Some listing projection work requires attention."
                : "Listing projection work has no recorded failures.", issues);
    }

    private Job job(String id, String type, String listingId, String rawState,
            int attempts, int max, Instant next, String claim, Instant claimExpiry,
            String error, String correlation, Instant completed, Instant created, Instant now) {
        boolean claimed = claim != null && claimExpiry != null && claimExpiry.isAfter(now);
        String status = "APPLIED".equals(rawState) ? "SUCCEEDED"
                : "TERMINAL".equals(rawState) ? "DEAD_LETTER"
                : attempts > 0 ? "FAILED" : "PENDING";
        return new Job(id, type, "PRODUCT", status, attempts, max, created, completed, next,
                correlation, error, summary(error), "FAILED".equals(status) && !claimed && attempts < max,
                "LISTING", listingId);
    }

    private static String summary(String code) {
        return code == null ? null : "The worker recorded " + code + ". Raw exception details are not exposed.";
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record ListingState(String id, long version, String sellerType, String status,
            String moderationStatus, String publicationSource) {
        boolean publiclySearchable() {
            return ("INDIVIDUAL".equals(sellerType) && "ACTIVE".equals(status)
                    && "APPROVED".equals(moderationStatus))
                    || ("BUSINESS".equals(sellerType) && "ACTIVE".equals(status)
                    && "BUSINESS_SELF_PUBLISHED".equals(publicationSource));
        }
    }
}
