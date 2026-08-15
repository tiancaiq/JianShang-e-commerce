package com.msb.ecom.product_service.search;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ListingSearchVectorApplyWorkRepository {

    private final JdbcTemplate jdbcTemplate;

    // Inserts one durable vector intent in the caller's receipt-acceptance transaction.
    public boolean insertIfAbsent(
            String workId,
            String requestId,
            String listingId,
            long listingVersion,
            Instant now) {
        return jdbcTemplate.update("""
                insert ignore into listing_search_vector_apply_work (
                    work_id, request_id, listing_id, listing_version, state,
                    attempt_count, next_attempt_at, claim_token, claim_expires_at,
                    last_error_code, completed_at, created_at, updated_at
                )
                values (?, ?, ?, ?, 'PENDING', 0, ?, null, null, null, null, ?, ?)
                """,
                workId,
                requestId,
                listingId,
                listingVersion,
                timestamp(now),
                timestamp(now),
                timestamp(now)) == 1;
    }

    public boolean existsByRequestId(String requestId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from listing_search_vector_apply_work
                where request_id = ?
                """, Integer.class, requestId);
        return count != null && count > 0;
    }

    @Transactional
    // Leases a bounded batch; an expired claim becomes available after worker restart.
    public List<ListingSearchVectorApplyWork> claimBatch(
            String claimToken,
            Instant now,
            Instant claimExpiresAt,
            int limit) {
        List<String> workIds = jdbcTemplate.queryForList("""
                select work_id
                from listing_search_vector_apply_work
                where state = 'PENDING'
                  and next_attempt_at <= ?
                  and (claim_token is null or claim_expires_at <= ?)
                order by work_sequence asc
                limit ?
                for update skip locked
                """,
                String.class,
                timestamp(now),
                timestamp(now),
                limit);
        if (workIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", workIds.stream().map(ignored -> "?").toList());
        Object[] arguments = new Object[workIds.size() + 2];
        arguments[0] = claimToken;
        arguments[1] = timestamp(claimExpiresAt);
        for (int index = 0; index < workIds.size(); index++) {
            arguments[index + 2] = workIds.get(index);
        }
        jdbcTemplate.update("""
                update listing_search_vector_apply_work
                set claim_token = ?, claim_expires_at = ?, updated_at = UTC_TIMESTAMP(6)
                where work_id in (%s) and state = 'PENDING'
                """.formatted(placeholders), arguments);
        return jdbcTemplate.query("""
                select work_id, request_id, listing_id, listing_version,
                       attempt_count, created_at
                from listing_search_vector_apply_work
                where claim_token = ? and state = 'PENDING'
                order by work_sequence asc
                """,
                (resultSet, rowNumber) -> new ListingSearchVectorApplyWork(
                        resultSet.getString("work_id"),
                        resultSet.getString("request_id"),
                        resultSet.getString("listing_id"),
                        resultSet.getLong("listing_version"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getTimestamp("created_at").toInstant()),
                claimToken);
    }

    public boolean markApplied(
            String workId,
            String claimToken,
            Instant completedAt) {
        return complete(workId, claimToken, "APPLIED", "APPLIED", completedAt, false);
    }

    public boolean markStale(
            String workId,
            String claimToken,
            Instant completedAt,
            String errorCode) {
        return complete(workId, claimToken, "STALE", errorCode, completedAt, false);
    }

    public boolean markTerminal(
            String workId,
            String claimToken,
            Instant completedAt,
            String errorCode) {
        return complete(workId, claimToken, "TERMINAL", errorCode, completedAt, true);
    }

    public boolean markRetry(
            String workId,
            String claimToken,
            Instant nextAttemptAt,
            String errorCode) {
        return jdbcTemplate.update("""
                update listing_search_vector_apply_work
                set attempt_count = attempt_count + 1,
                    next_attempt_at = ?,
                    last_error_code = ?,
                    claim_token = null,
                    claim_expires_at = null,
                    updated_at = UTC_TIMESTAMP(6)
                where work_id = ? and claim_token = ? and state = 'PENDING'
                """,
                timestamp(nextAttemptAt),
                errorCode,
                workId,
                claimToken) == 1;
    }

    public long pendingCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from listing_search_vector_apply_work
                where state = 'PENDING'
                """, Long.class);
        return count == null ? 0 : count;
    }

    private boolean complete(
            String workId,
            String claimToken,
            String state,
            String errorCode,
            Instant completedAt,
            boolean incrementAttempt) {
        return jdbcTemplate.update("""
                update listing_search_vector_apply_work
                set state = ?,
                    attempt_count = attempt_count + ?,
                    completed_at = ?,
                    last_error_code = ?,
                    claim_token = null,
                    claim_expires_at = null,
                    updated_at = ?
                where work_id = ? and claim_token = ? and state = 'PENDING'
                """,
                state,
                incrementAttempt ? 1 : 0,
                timestamp(completedAt),
                errorCode,
                timestamp(completedAt),
                workId,
                claimToken) == 1;
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
