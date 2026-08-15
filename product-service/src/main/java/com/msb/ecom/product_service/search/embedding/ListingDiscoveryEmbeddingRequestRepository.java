package com.msb.ecom.product_service.search.embedding;

import com.msb.ecom.product_service.knowledge.ListingKnowledgeOutboxEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ListingDiscoveryEmbeddingRequestRepository {

    private final JdbcTemplate jdbcTemplate;

    // Uses the database uniqueness contract so concurrent duplicate mutations create one durable request.
    public boolean insertIfAbsent(ListingDiscoveryEmbeddingRequest request) {
        return jdbcTemplate.update("""
                insert ignore into listing_discovery_embedding_requests (
                    request_id, event_id, listing_id, listing_version,
                    document_schema_version, document_hash,
                    embedding_input_schema_version, embedding_input_hash,
                    normalizer_version, redactor_version, language,
                    embedding_provider, embedding_model, embedding_dimensions,
                    state, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?)
                """,
                request.requestId(),
                request.eventId(),
                request.listingId(),
                request.listingVersion(),
                request.documentSchemaVersion(),
                request.documentHash(),
                request.embeddingInputSchemaVersion(),
                request.embeddingInputHash(),
                request.normalizerVersion(),
                request.redactorVersion(),
                request.language(),
                request.provider(),
                request.model(),
                request.dimensions(),
                timestamp(request.createdAt())) == 1;
    }

    public Optional<ListingDiscoveryEmbeddingRequest> findByRequestId(String requestId) {
        return findByRequestId(requestId, false);
    }

    // Serializes callback acceptance and replay decisions for one durable Product request.
    public Optional<ListingDiscoveryEmbeddingRequest> findByRequestIdForUpdate(String requestId) {
        return findByRequestId(requestId, true);
    }

    private Optional<ListingDiscoveryEmbeddingRequest> findByRequestId(
            String requestId,
            boolean forUpdate) {
        List<ListingDiscoveryEmbeddingRequest> rows = jdbcTemplate.query("""
                select request_id, event_id, listing_id, listing_version,
                       document_schema_version, document_hash,
                       embedding_input_schema_version, embedding_input_hash,
                       normalizer_version, redactor_version, language,
                       embedding_provider, embedding_model, embedding_dimensions,
                       state, created_at
                from listing_discovery_embedding_requests
                where request_id = ?
                """ + (forUpdate ? " for update" : ""),
                (resultSet, rowNumber) -> request(resultSet),
                requestId);
        return rows.stream().findFirst();
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

    private ListingDiscoveryEmbeddingRequest request(ResultSet resultSet) throws SQLException {
        return new ListingDiscoveryEmbeddingRequest(
                resultSet.getString("request_id"),
                resultSet.getString("event_id"),
                resultSet.getString("listing_id"),
                resultSet.getLong("listing_version"),
                resultSet.getString("document_schema_version"),
                resultSet.getString("document_hash"),
                resultSet.getString("embedding_input_schema_version"),
                resultSet.getString("embedding_input_hash"),
                resultSet.getString("normalizer_version"),
                resultSet.getString("redactor_version"),
                resultSet.getString("language"),
                resultSet.getString("embedding_provider"),
                resultSet.getString("embedding_model"),
                resultSet.getInt("embedding_dimensions"),
                resultSet.getString("state"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private Timestamp timestamp(java.time.Instant value) {
        return Timestamp.from(value);
    }
}
