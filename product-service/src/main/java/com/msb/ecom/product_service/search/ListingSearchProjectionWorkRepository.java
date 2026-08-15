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
public class ListingSearchProjectionWorkRepository {

    private final JdbcTemplate jdbcTemplate;

    // Inserts the projection intent in the caller's listing transaction.
    public void insert(
            String workId,
            String listingId,
            long listingVersion,
            String operation,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_search_projection_work (
                    work_id, listing_id, listing_version, operation, state,
                    attempt_count, next_attempt_at, claim_token, claim_expires_at,
                    last_error_code, completed_at, created_at
                )
                values (?, ?, ?, ?, 'PENDING', 0, ?, null, null, null, null, ?)
                """,
                workId,
                listingId,
                listingVersion,
                operation,
                timestamp(now),
                timestamp(now));
    }

    @Transactional
    // Leases a bounded batch so crashed workers recover after claim expiry.
    public List<ListingSearchProjectionWork> claimBatch(
            String claimToken,
            Instant now,
            Instant claimExpiresAt,
            int limit) {
        List<String> workIds = jdbcTemplate.queryForList("""
                select work_id
                from listing_search_projection_work
                where state = 'PENDING'
                  and next_attempt_at <= ?
                  and (claim_token is null or claim_expires_at <= ?)
                order by created_at asc, work_id asc
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
                update listing_search_projection_work
                set claim_token = ?, claim_expires_at = ?
                where work_id in (%s) and state = 'PENDING'
                """.formatted(placeholders), arguments);
        return jdbcTemplate.query("""
                select work_id, listing_id, listing_version, operation, attempt_count, created_at
                from listing_search_projection_work
                where claim_token = ? and state = 'PENDING'
                order by created_at asc, work_id asc
                """,
                (rs, rowNum) -> new ListingSearchProjectionWork(
                        rs.getString("work_id"),
                        rs.getString("listing_id"),
                        rs.getLong("listing_version"),
                        rs.getString("operation"),
                        rs.getInt("attempt_count"),
                        rs.getTimestamp("created_at").toInstant()),
                claimToken);
    }

    public boolean markApplied(String workId, String claimToken, Instant completedAt, String resultCode) {
        return jdbcTemplate.update("""
                update listing_search_projection_work
                set state = 'APPLIED', completed_at = ?, last_error_code = ?,
                    claim_token = null, claim_expires_at = null
                where work_id = ? and claim_token = ? and state = 'PENDING'
                """,
                timestamp(completedAt),
                resultCode,
                workId,
                claimToken) == 1;
    }

    public boolean markRetry(
            String workId,
            String claimToken,
            Instant nextAttemptAt,
            String errorCode) {
        return jdbcTemplate.update("""
                update listing_search_projection_work
                set attempt_count = attempt_count + 1, next_attempt_at = ?,
                    last_error_code = ?, claim_token = null, claim_expires_at = null
                where work_id = ? and claim_token = ? and state = 'PENDING'
                """,
                timestamp(nextAttemptAt),
                errorCode,
                workId,
                claimToken) == 1;
    }

    public boolean markTerminal(
            String workId,
            String claimToken,
            Instant completedAt,
            String errorCode) {
        return jdbcTemplate.update("""
                update listing_search_projection_work
                set state = 'TERMINAL', attempt_count = attempt_count + 1,
                    completed_at = ?, last_error_code = ?,
                    claim_token = null, claim_expires_at = null
                where work_id = ? and claim_token = ? and state = 'PENDING'
                """,
                timestamp(completedAt),
                errorCode,
                workId,
                claimToken) == 1;
    }

    public long pendingCount() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from listing_search_projection_work where state = 'PENDING'
                """, Long.class);
        return count == null ? 0 : count;
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
