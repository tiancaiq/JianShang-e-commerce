package com.msb.ecom.auth_service.appeals;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AppealResolutionCommandService {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Reservation> find(String idempotencyKey) {
        return jdbc.query("""
                select idempotency_key, appeal_id, request_hash, executor_admin_id, created_at
                from appeal_resolution_commands where idempotency_key = ?
                """, (rs, row) -> new Reservation(rs.getString("idempotency_key"),
                rs.getString("appeal_id"), rs.getString("request_hash"),
                rs.getString("executor_admin_id"), rs.getTimestamp("created_at").toInstant(), false),
                idempotencyKey).stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    // Commits the global key mapping before any remote owner mutation, closing
    // the gap where Product could commit while Auth later lost a key race.
    public Reservation reserve(String idempotencyKey, String appealId, String requestHash,
                               String executorAdminId, Instant now) {
        try {
            jdbc.update("""
                    insert into appeal_resolution_commands
                        (idempotency_key, appeal_id, request_hash, executor_admin_id, created_at)
                    values (?, ?, ?, ?, ?)
                    """, idempotencyKey, appealId, requestHash, executorAdminId, Timestamp.from(now));
            return new Reservation(idempotencyKey, appealId, requestHash, executorAdminId, now, true);
        } catch (DuplicateKeyException exception) {
            Reservation existing = find(idempotencyKey).orElseThrow(() ->
                    AppealException.conflict("APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT",
                            "The resolution key is already reserved."));
            requireMatch(existing, appealId, requestHash, executorAdminId);
            return existing;
        }
    }

    public void requireMatch(Reservation reservation, String appealId, String requestHash,
                             String executorAdminId) {
        if (!reservation.appealId().equals(appealId)
                || !reservation.requestHash().equals(requestHash)
                || !reservation.executorAdminId().equals(executorAdminId)) {
            throw AppealException.conflict("APPEAL_RESOLUTION_IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for a different appeal resolution.");
        }
    }

    public record Reservation(String idempotencyKey, String appealId, String requestHash,
                              String executorAdminId, Instant createdAt, boolean created) { }
}
