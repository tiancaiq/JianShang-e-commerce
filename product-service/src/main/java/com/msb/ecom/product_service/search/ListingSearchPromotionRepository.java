package com.msb.ecom.product_service.search;

import com.msb.ecom.product_service.search.embedding.ListingDiscoveryEmbeddingSourceBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
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
public class ListingSearchPromotionRepository {

    private static final String COORDINATION_KEY = "PUBLIC_LISTING";
    private static final String ACTIVE_STATES =
            "'PREPARING','DUAL_WRITE','BACKFILLING','CATCHING_UP','PROMOTION_FENCED','ROLLBACK_REQUIRED'";

    private final JdbcTemplate jdbcTemplate;

    // Shares the singleton row with listing mutations so an exclusive promotion fence blocks their commit.
    public void acquireMutationFenceShared(int lockWaitSeconds) {
        acquireFence("for share", lockWaitSeconds);
    }

    // Exclusively fences listing-intent commits for the bounded final drain and atomic alias movement.
    public void acquirePromotionFenceExclusive(int lockWaitSeconds) {
        acquireFence("for update", lockWaitSeconds);
    }

    public long currentWorkSequence() {
        Long value = jdbcTemplate.queryForObject(
                "select coalesce(max(work_sequence), 0) from listing_search_projection_work",
                Long.class);
        return value == null ? 0 : value;
    }

    public long currentReceiptSequence() {
        Long value = jdbcTemplate.queryForObject(
                "select coalesce(max(receipt_sequence), 0) from listing_discovery_embedding_receipts",
                Long.class);
        return value == null ? 0 : value;
    }

    public void insertPreparingRun(
            String runId,
            String candidateGeneration,
            String previousReadGeneration,
            String previousWriteGeneration,
            long startWorkSequence,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_search_rebuild_runs (
                    run_id, candidate_generation, previous_read_generation,
                    previous_write_generation, schema_identity,
                    embedding_provider, embedding_model, embedding_dimensions,
                    start_work_sequence, receipt_watermark_sequence, state,
                    authoritative_document_count, vector_document_count,
                    catch_up_work_count, deferred_receipt_count,
                    last_error_code, started_at, updated_at, promoted_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, 'PREPARING',
                    0, 0, 0, 0, null, ?, ?, null)
                """,
                runId,
                candidateGeneration,
                previousReadGeneration,
                previousWriteGeneration,
                OpenSearchListingVectorBackfillClient.SCHEMA_IDENTITY,
                ListingDiscoveryEmbeddingSourceBuilder.PROVIDER,
                ListingDiscoveryEmbeddingSourceBuilder.MODEL,
                ListingDiscoveryEmbeddingSourceBuilder.DIMENSIONS,
                startWorkSequence,
                timestamp(now),
                timestamp(now));
    }

    public Optional<ListingSearchRebuildRun> findActiveRun() {
        return query("""
                select * from listing_search_rebuild_runs
                where state in (%s)
                order by started_at desc
                limit 1
                """.formatted(ACTIVE_STATES));
    }

    public Optional<ListingSearchRebuildRun> findDualWriteRun() {
        return query("""
                select * from listing_search_rebuild_runs
                where state in ('DUAL_WRITE','BACKFILLING','CATCHING_UP','PROMOTION_FENCED')
                order by started_at desc
                limit 1
                """);
    }

    public Optional<ListingSearchRebuildRun> findById(String runId) {
        return query("select * from listing_search_rebuild_runs where run_id = ?", runId);
    }

    public Optional<ListingSearchRebuildRun> lockById(String runId) {
        return query("select * from listing_search_rebuild_runs where run_id = ? for update", runId);
    }

    public void activateDualWrite(String runId, long receiptWatermarkSequence, Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = 'DUAL_WRITE', receipt_watermark_sequence = ?, updated_at = ?
                where run_id = ? and state = 'PREPARING'
                """, receiptWatermarkSequence, timestamp(now), runId));
    }

    public void startBackfill(String runId, Instant now) {
        updateState(runId, "DUAL_WRITE", "BACKFILLING", now);
    }

    public void recordBackfill(
            String runId,
            int documentCount,
            int vectorCount,
            int deferredReceiptCount,
            Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = 'CATCHING_UP',
                    authoritative_document_count = ?,
                    vector_document_count = ?,
                    deferred_receipt_count = ?,
                    updated_at = ?
                where run_id = ? and state = 'BACKFILLING'
                """,
                documentCount,
                vectorCount,
                deferredReceiptCount,
                timestamp(now),
                runId));
    }

    public void recordCatchUp(String runId, int catchUpCount, Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set catch_up_work_count = ?, updated_at = ?
                where run_id = ? and state = 'CATCHING_UP'
                """, catchUpCount, timestamp(now), runId));
    }

    public void markPromotionFenced(String runId, Instant now) {
        updateState(runId, "CATCHING_UP", "PROMOTION_FENCED", now);
    }

    public void markPromoted(String runId, Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = 'PROMOTED', last_error_code = null,
                    promoted_at = ?, updated_at = ?
                where run_id = ? and state = 'PROMOTION_FENCED'
                """, timestamp(now), timestamp(now), runId));
    }

    public void markRecoveredPromoted(String runId, Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = 'PROMOTED', last_error_code = null,
                    promoted_at = coalesce(promoted_at, ?), updated_at = ?
                where run_id = ?
                  and state in ('CATCHING_UP','PROMOTION_FENCED','ROLLBACK_REQUIRED')
                """, timestamp(now), timestamp(now), runId));
    }

    public void restoreCatchingUp(String runId, Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = 'CATCHING_UP', last_error_code = null, updated_at = ?
                where run_id = ?
                  and state in ('PROMOTION_FENCED','ROLLBACK_REQUIRED')
                """, timestamp(now), runId));
    }

    public void markFailed(String runId, String expectedState, String state, String errorCode, Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = ?, last_error_code = ?, updated_at = ?
                where run_id = ? and state = ?
                """, state, errorCode, timestamp(now), runId, expectedState));
    }

    public List<ProjectionWorkBoundary> workAfter(long sequence, int limit) {
        return jdbcTemplate.query("""
                select work_sequence, work_id, listing_id, listing_version, operation, state
                from listing_search_projection_work
                where work_sequence > ?
                order by work_sequence asc
                limit ?
                """,
                (resultSet, rowNumber) -> new ProjectionWorkBoundary(
                        resultSet.getLong("work_sequence"),
                        resultSet.getString("work_id"),
                        resultSet.getString("listing_id"),
                        resultSet.getLong("listing_version"),
                        resultSet.getString("operation"),
                        resultSet.getString("state")),
                sequence,
                limit);
    }

    public long unresolvedWorkCount() {
        Long value = jdbcTemplate.queryForObject("""
                select count(*) from listing_search_projection_work
                where state <> 'APPLIED'
                """, Long.class);
        return value == null ? 0 : value;
    }

    private void updateState(
            String runId,
            String expectedState,
            String targetState,
            Instant now) {
        requireUpdated(jdbcTemplate.update("""
                update listing_search_rebuild_runs
                set state = ?, updated_at = ?
                where run_id = ? and state = ?
                """, targetState, timestamp(now), runId, expectedState));
    }

    private Optional<ListingSearchRebuildRun> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, this::run, arguments).stream().findFirst();
    }

    private ListingSearchRebuildRun run(ResultSet resultSet, int rowNumber) throws SQLException {
        Timestamp promotedAt = resultSet.getTimestamp("promoted_at");
        return new ListingSearchRebuildRun(
                resultSet.getString("run_id"),
                resultSet.getString("candidate_generation"),
                resultSet.getString("previous_read_generation"),
                resultSet.getString("previous_write_generation"),
                resultSet.getLong("start_work_sequence"),
                resultSet.getObject("receipt_watermark_sequence") == null
                        ? null
                        : resultSet.getLong("receipt_watermark_sequence"),
                resultSet.getString("state"),
                resultSet.getInt("authoritative_document_count"),
                resultSet.getInt("vector_document_count"),
                resultSet.getInt("catch_up_work_count"),
                resultSet.getInt("deferred_receipt_count"),
                resultSet.getString("last_error_code"),
                resultSet.getTimestamp("started_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant(),
                promotedAt == null ? null : promotedAt.toInstant());
    }

    private void acquireFence(String lockClause, int lockWaitSeconds) {
        int bounded = Math.max(1, Math.min(lockWaitSeconds, 30));
        Integer previous = jdbcTemplate.queryForObject(
                "select @@session.innodb_lock_wait_timeout", Integer.class);
        try {
            jdbcTemplate.execute("set session innodb_lock_wait_timeout = " + bounded);
            String key = jdbcTemplate.queryForObject("""
                    select coordination_key
                    from listing_search_projection_coordination
                    where coordination_key = ?
                    %s
                    """.formatted(lockClause), String.class, COORDINATION_KEY);
            if (!COORDINATION_KEY.equals(key)) {
                throw new ListingSearchUnavailableException("Listing search projection fence is unavailable.");
            }
        } catch (DataAccessException exception) {
            throw new ListingSearchUnavailableException(
                    "Listing search projection fence is busy.", exception);
        } finally {
            if (previous != null) {
                jdbcTemplate.execute("set session innodb_lock_wait_timeout = " + previous);
            }
        }
    }

    private void requireUpdated(int rows) {
        if (rows != 1) {
            throw new ListingSearchUnavailableException(
                    "Listing search rebuild state transition was rejected.");
        }
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    public record ProjectionWorkBoundary(
            long sequence,
            String workId,
            String listingId,
            long listingVersion,
            String operation,
            String state
    ) {
    }
}
