package com.msb.ecom.product_service.search.embedding;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ListingEmbeddingRequestBackfillRepository {
    private final JdbcTemplate jdbcTemplate;

    public void insert(
            String runId,
            String upperBoundListingId,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_embedding_request_backfill_runs (
                    run_id, upper_bound_listing_id, last_processed_listing_id,
                    state, page_count, processed_count, created_count,
                    already_present_count, skipped_count, failed_count,
                    last_error_code, lease_token, lease_expires_at,
                    started_at, updated_at, completed_at
                ) values (?, ?, null, 'PENDING', 0, 0, 0, 0, 0, 0,
                          null, null, null, ?, ?, null)
                """,
                runId,
                upperBoundListingId,
                timestamp(now),
                timestamp(now));
    }

    public Optional<ListingEmbeddingRequestBackfillRun> findActive() {
        return query("""
                select *
                from listing_embedding_request_backfill_runs
                where active_slot = 1
                limit 1
                """).stream().findFirst();
    }

    public Optional<ListingEmbeddingRequestBackfillRun> findByRunId(String runId) {
        return query("""
                select *
                from listing_embedding_request_backfill_runs
                where run_id = ?
                """, runId).stream().findFirst();
    }

    // Claims one bounded page; an expired RUNNING lease is restart-safe.
    public boolean claim(
            String runId,
            String leaseToken,
            Instant now,
            Instant leaseExpiresAt) {
        return jdbcTemplate.update("""
                update listing_embedding_request_backfill_runs
                set state = 'RUNNING',
                    lease_token = ?,
                    lease_expires_at = ?,
                    last_error_code = null,
                    updated_at = ?
                where run_id = ?
                  and (
                    state in ('PENDING', 'FAILED')
                    or (
                        state = 'RUNNING'
                        and lease_expires_at <= ?
                    )
                  )
                """,
                leaseToken,
                timestamp(leaseExpiresAt),
                timestamp(now),
                runId,
                timestamp(now)) == 1;
    }

    // Advances the durable cursor only after every per-listing transaction in the page committed.
    public boolean completePage(
            String runId,
            String leaseToken,
            ListingEmbeddingRequestBackfillPageResult page,
            Instant now) {
        String nextState = page.hasMore() ? "PENDING" : "COMPLETED";
        Timestamp completedAt = page.hasMore() ? null : timestamp(now);
        return jdbcTemplate.update("""
                update listing_embedding_request_backfill_runs
                set state = ?,
                    last_processed_listing_id = coalesce(?, last_processed_listing_id),
                    page_count = page_count + 1,
                    processed_count = processed_count + ?,
                    created_count = created_count + ?,
                    already_present_count = already_present_count + ?,
                    skipped_count = skipped_count + ?,
                    last_error_code = null,
                    lease_token = null,
                    lease_expires_at = null,
                    updated_at = ?,
                    completed_at = ?
                where run_id = ?
                  and state = 'RUNNING'
                  and lease_token = ?
                """,
                nextState,
                page.lastProcessedListingId(),
                page.processed(),
                page.created(),
                page.alreadyPresent(),
                page.skipped(),
                timestamp(now),
                completedAt,
                runId,
                leaseToken) == 1;
    }

    public boolean fail(
            String runId,
            String leaseToken,
            String errorCode,
            Instant now) {
        return jdbcTemplate.update("""
                update listing_embedding_request_backfill_runs
                set state = 'FAILED',
                    failed_count = failed_count + 1,
                    last_error_code = ?,
                    lease_token = null,
                    lease_expires_at = null,
                    updated_at = ?
                where run_id = ?
                  and state = 'RUNNING'
                  and lease_token = ?
                """,
                errorCode,
                timestamp(now),
                runId,
                leaseToken) == 1;
    }

    private List<ListingEmbeddingRequestBackfillRun> query(String sql, Object... args) {
        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> row(resultSet), args);
    }

    private ListingEmbeddingRequestBackfillRun row(ResultSet resultSet) throws SQLException {
        return new ListingEmbeddingRequestBackfillRun(
                resultSet.getString("run_id"),
                resultSet.getString("upper_bound_listing_id"),
                resultSet.getString("last_processed_listing_id"),
                resultSet.getString("state"),
                resultSet.getInt("page_count"),
                resultSet.getInt("processed_count"),
                resultSet.getInt("created_count"),
                resultSet.getInt("already_present_count"),
                resultSet.getInt("skipped_count"),
                resultSet.getInt("failed_count"),
                resultSet.getString("last_error_code"),
                resultSet.getString("lease_token"),
                instant(resultSet.getTimestamp("lease_expires_at")),
                resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                instant(resultSet.getTimestamp("completed_at")));
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
