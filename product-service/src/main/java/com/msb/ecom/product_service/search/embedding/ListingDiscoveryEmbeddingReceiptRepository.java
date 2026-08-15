package com.msb.ecom.product_service.search.embedding;

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
public class ListingDiscoveryEmbeddingReceiptRepository {

    private final JdbcTemplate jdbcTemplate;

    // Inserts only the canonical fixed-width derived vector selected by the owning service transaction.
    public boolean insert(ListingDiscoveryEmbeddingReceipt receipt) {
        return jdbcTemplate.update("""
                insert into listing_discovery_embedding_receipts (
                    request_id, listing_id, listing_version,
                    document_schema_version, document_hash,
                    embedding_input_schema_version, embedding_input_hash,
                    normalizer_version, redactor_version, language,
                    embedding_provider, embedding_model, embedding_dimensions,
                    vector_hash, vector_bytes, accepted_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receipt.requestId(),
                receipt.listingId(),
                receipt.listingVersion(),
                receipt.documentSchemaVersion(),
                receipt.documentHash(),
                receipt.embeddingInputSchemaVersion(),
                receipt.embeddingInputHash(),
                receipt.normalizerVersion(),
                receipt.redactorVersion(),
                receipt.language(),
                receipt.provider(),
                receipt.model(),
                receipt.dimensions(),
                receipt.vectorHash(),
                receipt.vectorBytes(),
                Timestamp.from(receipt.acceptedAt())) == 1;
    }

    public Optional<ListingDiscoveryEmbeddingReceipt> findByRequestId(String requestId) {
        return query("""
                select request_id, listing_id, listing_version,
                       document_schema_version, document_hash,
                       embedding_input_schema_version, embedding_input_hash,
                       normalizer_version, redactor_version, language,
                       embedding_provider, embedding_model, embedding_dimensions,
                       vector_hash, vector_bytes, accepted_at
                from listing_discovery_embedding_receipts
                where request_id = ?
                """, requestId);
    }

    // Pages accepted receipts that have no durable vector-apply outcome in stable receipt order.
    public List<ReceiptCursor> findWithoutVectorWorkAfter(
            long afterReceiptSequence,
            int limit) {
        return jdbcTemplate.query("""
                select r.receipt_sequence,
                       r.request_id, r.listing_id, r.listing_version,
                       r.document_schema_version, r.document_hash,
                       r.embedding_input_schema_version, r.embedding_input_hash,
                       r.normalizer_version, r.redactor_version, r.language,
                       r.embedding_provider, r.embedding_model, r.embedding_dimensions,
                       r.vector_hash, r.vector_bytes, r.accepted_at
                from listing_discovery_embedding_receipts r
                left join listing_search_vector_apply_work w
                       on w.request_id = r.request_id
                where r.receipt_sequence > ?
                  and w.request_id is null
                order by r.receipt_sequence asc
                limit ?
                """,
                (resultSet, rowNumber) -> new ReceiptCursor(
                        resultSet.getLong("receipt_sequence"),
                        receipt(resultSet)),
                afterReceiptSequence,
                limit);
    }

    // Selects a rebuild input only when Product MySQL still exposes the exact eligible listing identity.
    public Optional<ListingDiscoveryEmbeddingReceipt> findExactCurrentForRebuild(
            String listingId,
            long listingVersion,
            String documentHash,
            String embeddingInputHash,
            String normalizerVersion,
            String redactorVersion,
            String language,
            String provider,
            String model,
            int dimensions) {
        return findExactCurrentForRebuild(
                listingId,
                listingVersion,
                documentHash,
                embeddingInputHash,
                normalizerVersion,
                redactorVersion,
                language,
                provider,
                model,
                dimensions,
                null);
    }

    // Restricts rebuild selection to the run's durable receipt watermark.
    public Optional<ListingDiscoveryEmbeddingReceipt> findExactCurrentForRebuild(
            String listingId,
            long listingVersion,
            String documentHash,
            String embeddingInputHash,
            String normalizerVersion,
            String redactorVersion,
            String language,
            String provider,
            String model,
            int dimensions,
            Long maximumReceiptSequence) {
        return query("""
                select r.request_id, r.listing_id, r.listing_version,
                       r.document_schema_version, r.document_hash,
                       r.embedding_input_schema_version, r.embedding_input_hash,
                       r.normalizer_version, r.redactor_version, r.language,
                       r.embedding_provider, r.embedding_model, r.embedding_dimensions,
                       r.vector_hash, r.vector_bytes, r.accepted_at
                from listing_discovery_embedding_receipts r
                join listings l on l.id = r.listing_id
                where r.listing_id = ?
                  and r.listing_version = ?
                  and r.document_hash = ?
                  and r.embedding_input_hash = ?
                  and r.normalizer_version = ?
                  and r.redactor_version = ?
                  and r.language = ?
                  and r.embedding_provider = ?
                  and r.embedding_model = ?
                  and r.embedding_dimensions = ?
                  and (? is null or r.receipt_sequence <= ?)
                  and l.version = r.listing_version
                  and l.seller_type = 'INDIVIDUAL'
                  and l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                """,
                listingId,
                listingVersion,
                documentHash,
                embeddingInputHash,
                normalizerVersion,
                redactorVersion,
                language,
                provider,
                model,
                dimensions,
                maximumReceiptSequence,
                maximumReceiptSequence);
    }

    private Optional<ListingDiscoveryEmbeddingReceipt> query(
            String sql,
            Object... arguments) {
        List<ListingDiscoveryEmbeddingReceipt> rows = jdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> receipt(resultSet),
                arguments);
        return rows.stream().findFirst();
    }

    private ListingDiscoveryEmbeddingReceipt receipt(ResultSet resultSet)
            throws SQLException {
        return new ListingDiscoveryEmbeddingReceipt(
                resultSet.getString("request_id"),
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
                resultSet.getString("vector_hash"),
                resultSet.getBytes("vector_bytes"),
                resultSet.getTimestamp("accepted_at").toInstant());
    }

    public record ReceiptCursor(
            long receiptSequence,
            ListingDiscoveryEmbeddingReceipt receipt
    ) {
    }
}
