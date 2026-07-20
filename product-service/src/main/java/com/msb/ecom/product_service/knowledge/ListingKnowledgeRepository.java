package com.msb.ecom.product_service.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ListingKnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insertActive(ListingKnowledgeVersion version) {
        jdbcTemplate.update("""
                insert into listing_knowledge_versions (
                    listing_id, source_version, supersedes_version, lifecycle, seller_type,
                    visibility, language, title, description, price_amount, currency,
                    public_city, public_region, content_hash, effective_from,
                    invalidated_at, source_published_at, created_at
                )
                values (?, ?, ?, 'ACTIVE', 'INDIVIDUAL', 'PUBLIC', 'und', ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?)
                """,
                version.listingId(),
                version.sourceVersion(),
                version.supersedesVersion(),
                version.title(),
                version.description(),
                version.priceAmount(),
                version.currency(),
                version.publicCity(),
                version.publicRegion(),
                version.contentHash(),
                timestamp(version.effectiveFrom()),
                timestamp(version.sourcePublishedAt()),
                timestamp(version.createdAt()));
    }

    public void insertInvalidated(ListingKnowledgeVersion version) {
        jdbcTemplate.update("""
                insert into listing_knowledge_versions (
                    listing_id, source_version, supersedes_version, lifecycle, seller_type,
                    visibility, language, title, description, price_amount, currency,
                    public_city, public_region, content_hash, effective_from,
                    invalidated_at, source_published_at, created_at
                )
                values (?, ?, ?, 'INVALIDATED', 'INDIVIDUAL', 'PUBLIC', 'und',
                        null, null, null, null, null, null, null, null, ?, null, ?)
                """,
                version.listingId(),
                version.sourceVersion(),
                version.supersedesVersion(),
                timestamp(version.invalidatedAt()),
                timestamp(version.createdAt()));
    }

    public Optional<ListingKnowledgeVersion> findExact(String listingId, long sourceVersion) {
        List<ListingKnowledgeVersion> rows = jdbcTemplate.query("""
                select listing_id, source_version, supersedes_version, lifecycle, seller_type,
                       visibility, language, title, description, price_amount, currency,
                       public_city, public_region, content_hash, effective_from,
                       invalidated_at, source_published_at, created_at
                from listing_knowledge_versions
                where listing_id = ? and source_version = ?
                """,
                (rs, rowNum) -> version(rs),
                listingId,
                sourceVersion);
        return rows.stream().findFirst();
    }

    public Optional<ListingKnowledgeVersion> findLatest(String listingId) {
        List<ListingKnowledgeVersion> rows = jdbcTemplate.query("""
                select listing_id, source_version, supersedes_version, lifecycle, seller_type,
                       visibility, language, title, description, price_amount, currency,
                       public_city, public_region, content_hash, effective_from,
                       invalidated_at, source_published_at, created_at
                from listing_knowledge_versions
                where listing_id = ?
                order by source_version desc
                limit 1
                """,
                (rs, rowNum) -> version(rs),
                listingId);
        return rows.stream().findFirst();
    }

    // Exports only the latest version per listing as it existed at the fixed rebuild watermark.
    public List<ListingKnowledgeVersion> findActiveAtWatermark(
            Instant watermark,
            String afterListingId,
            int limit) {
        return jdbcTemplate.query("""
                select kv.listing_id, kv.source_version, kv.supersedes_version, kv.lifecycle,
                       kv.seller_type, kv.visibility, kv.language, kv.title, kv.description,
                       kv.price_amount, kv.currency, kv.public_city, kv.public_region,
                       kv.content_hash, kv.effective_from, kv.invalidated_at,
                       kv.source_published_at, kv.created_at
                from listing_knowledge_versions kv
                join (
                    select listing_id, max(source_version) as source_version
                    from listing_knowledge_versions
                    where created_at <= ?
                    group by listing_id
                ) latest
                  on latest.listing_id = kv.listing_id
                 and latest.source_version = kv.source_version
                where kv.lifecycle = 'ACTIVE'
                  and (? is null or kv.listing_id > ?)
                order by kv.listing_id asc
                limit ?
                """,
                (rs, rowNum) -> version(rs),
                timestamp(watermark),
                afterListingId,
                afterListingId,
                limit);
    }

    public void insertOutbox(ListingKnowledgeOutboxEvent event, String deduplicationKey) {
        jdbcTemplate.update("""
                insert into outbox_events (
                    event_id, topic, message_key, aggregate_type, aggregate_id,
                    event_type, event_version, producer, occurred_at, correlation_id,
                    payload_json, deduplication_key, published_at, retry_count,
                    next_attempt_at, claim_token, claim_expires_at, last_error_code, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, 0, ?, null, null, null, ?)
                """,
                event.eventId(),
                event.topic(),
                event.messageKey(),
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.eventVersion(),
                event.producer(),
                timestamp(event.occurredAt()),
                event.correlationId(),
                event.payloadJson(),
                deduplicationKey,
                timestamp(event.createdAt()),
                timestamp(event.createdAt()));
    }

    @Transactional
    // Claims a bounded batch in a short transaction so multiple publisher instances cannot send the same live claim.
    public List<ListingKnowledgeOutboxEvent> claimBatch(
            String claimToken,
            Instant now,
            Instant claimExpiresAt,
            int limit) {
        List<String> eventIds = jdbcTemplate.queryForList("""
                select event_id
                from outbox_events
                where published_at is null
                  and next_attempt_at <= ?
                  and (claim_token is null or claim_expires_at <= ?)
                order by created_at asc, event_id asc
                limit ?
                for update skip locked
                """,
                String.class,
                timestamp(now),
                timestamp(now),
                limit);
        if (eventIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", eventIds.stream().map(id -> "?").toList());
        Object[] claimArguments = new Object[eventIds.size() + 2];
        claimArguments[0] = claimToken;
        claimArguments[1] = timestamp(claimExpiresAt);
        for (int index = 0; index < eventIds.size(); index++) {
            claimArguments[index + 2] = eventIds.get(index);
        }
        jdbcTemplate.update("""
                update outbox_events
                set claim_token = ?, claim_expires_at = ?
                where event_id in (%s)
                """.formatted(placeholders), claimArguments);
        return jdbcTemplate.query("""
                select event_id, topic, message_key, aggregate_type, aggregate_id,
                       event_type, event_version, producer, occurred_at, correlation_id,
                       payload_json, retry_count, created_at
                from outbox_events
                where claim_token = ?
                order by created_at asc, event_id asc
                """,
                (rs, rowNum) -> outboxEvent(rs),
                claimToken);
    }

    public boolean markPublished(String eventId, String claimToken, Instant publishedAt) {
        return jdbcTemplate.update("""
                update outbox_events
                set published_at = ?, claim_token = null, claim_expires_at = null,
                    last_error_code = null
                where event_id = ? and claim_token = ? and published_at is null
                """,
                timestamp(publishedAt),
                eventId,
                claimToken) == 1;
    }

    public boolean markFailed(
            String eventId,
            String claimToken,
            Instant nextAttemptAt,
            String errorCode) {
        return jdbcTemplate.update("""
                update outbox_events
                set retry_count = retry_count + 1,
                    next_attempt_at = ?,
                    claim_token = null,
                    claim_expires_at = null,
                    last_error_code = ?
                where event_id = ? and claim_token = ? and published_at is null
                """,
                timestamp(nextAttemptAt),
                errorCode,
                eventId,
                claimToken) == 1;
    }

    public long unpublishedCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from outbox_events where published_at is null",
                Long.class);
        return count == null ? 0 : count;
    }

    public double oldestUnpublishedAgeSeconds() {
        Timestamp oldest = jdbcTemplate.queryForObject(
                "select min(created_at) from outbox_events where published_at is null",
                Timestamp.class);
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest.toInstant(), Instant.now()).toSeconds());
    }

    private ListingKnowledgeVersion version(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListingKnowledgeVersion(
                rs.getString("listing_id"),
                rs.getLong("source_version"),
                nullableLong(rs, "supersedes_version"),
                rs.getString("lifecycle"),
                rs.getString("seller_type"),
                rs.getString("visibility"),
                rs.getString("language"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getBigDecimal("price_amount"),
                rs.getString("currency"),
                rs.getString("public_city"),
                rs.getString("public_region"),
                rs.getString("content_hash"),
                nullableInstant(rs, "effective_from"),
                nullableInstant(rs, "invalidated_at"),
                nullableInstant(rs, "source_published_at"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ListingKnowledgeOutboxEvent outboxEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListingKnowledgeOutboxEvent(
                rs.getString("event_id"),
                rs.getString("topic"),
                rs.getString("message_key"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("producer"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("correlation_id"),
                rs.getString("payload_json"),
                rs.getInt("retry_count"),
                rs.getTimestamp("created_at").toInstant());
    }

    private Long nullableLong(java.sql.ResultSet rs, String columnName) throws java.sql.SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(java.sql.ResultSet rs, String columnName) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(columnName);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
