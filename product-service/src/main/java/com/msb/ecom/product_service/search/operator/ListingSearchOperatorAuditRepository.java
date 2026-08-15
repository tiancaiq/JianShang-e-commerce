package com.msb.ecom.product_service.search.operator;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class ListingSearchOperatorAuditRepository {
    private final JdbcTemplate jdbcTemplate;

    // Appends one immutable protected audit row even when the operator command rolled back.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(
            String auditId,
            String adminUserId,
            String action,
            String runId,
            String fromState,
            String toState,
            String outcome,
            String errorCode,
            String correlationId,
            Instant occurredAt) {
        jdbcTemplate.update("""
                insert into listing_search_operator_audit (
                    audit_id, admin_user_id, action, run_id,
                    from_state, to_state, outcome, error_code,
                    correlation_id, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                adminUserId,
                action,
                runId,
                fromState,
                toState,
                outcome,
                errorCode,
                correlationId,
                Timestamp.from(occurredAt));
    }

    // Appends one immutable embedding-backfill admin audit without catalog content or cursor data.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendEmbeddingBackfill(
            String auditId,
            String adminUserId,
            String action,
            String runId,
            String fromState,
            String toState,
            String outcome,
            String errorCode,
            String correlationId,
            Instant occurredAt) {
        jdbcTemplate.update("""
                insert into listing_search_operator_audit (
                    audit_id, admin_user_id, action, run_id,
                    embedding_backfill_run_id, from_state, to_state,
                    outcome, error_code, correlation_id, occurred_at
                ) values (?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?)
                """,
                auditId,
                adminUserId,
                action,
                runId,
                fromState,
                toState,
                outcome,
                errorCode,
                correlationId,
                Timestamp.from(occurredAt));
    }
}
