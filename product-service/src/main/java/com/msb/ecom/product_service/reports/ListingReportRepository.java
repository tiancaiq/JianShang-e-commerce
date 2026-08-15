package com.msb.ecom.product_service.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
class ListingReportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    // Reserves one durable actor/key slot without a delete/insert gap-lock cycle under concurrent intake.
    boolean reserveIdempotency(
            String reporterUserId,
            String idempotencyKey,
            String requestHash,
            Instant expiresAt,
            Instant now) {
        return jdbcTemplate.update("""
                insert into listing_report_idempotency (
                    reporter_user_id, idempotency_key, request_hash, report_id,
                    expires_at, created_at, updated_at
                ) values (?, ?, ?, null, ?, ?, ?)
                on duplicate key update
                    request_hash = if(expires_at <= values(created_at), values(request_hash), request_hash),
                    report_id = if(expires_at <= values(created_at), null, report_id),
                    created_at = if(expires_at <= values(created_at), values(created_at), created_at),
                    updated_at = if(expires_at <= values(created_at), values(updated_at), updated_at),
                    expires_at = if(expires_at <= values(created_at), values(expires_at), expires_at)
                """,
                reporterUserId,
                idempotencyKey,
                requestHash,
                timestamp(expiresAt),
                timestamp(now),
                timestamp(now)) > 0;
    }

    Optional<IdempotencyReservation> lockIdempotency(String reporterUserId, String idempotencyKey) {
        return jdbcTemplate.query("""
                select request_hash, report_id, expires_at
                from listing_report_idempotency
                where reporter_user_id = ? and idempotency_key = ?
                for update
                """,
                (resultSet, rowNum) -> new IdempotencyReservation(
                        resultSet.getString("request_hash"),
                        resultSet.getString("report_id"),
                        resultSet.getTimestamp("expires_at").toInstant()),
                reporterUserId,
                idempotencyKey).stream().findFirst();
    }

    void completeIdempotency(String reporterUserId, String idempotencyKey, String reportId, Instant now) {
        int updated = jdbcTemplate.update("""
                update listing_report_idempotency
                set report_id = ?, updated_at = ?
                where reporter_user_id = ? and idempotency_key = ? and report_id is null
                """, reportId, timestamp(now), reporterUserId, idempotencyKey);
        if (updated != 1) {
            throw new ListingReportUnavailableException();
        }
    }

    // Reserves the semantic duplicate identity without a delete/insert gap-lock cycle under concurrent intake.
    boolean reserveDuplicateWindow(
            String reporterUserId,
            String listingId,
            String reasonCode,
            Instant expiresAt,
            Instant now) {
        return jdbcTemplate.update("""
                insert into listing_report_duplicate_windows (
                    reporter_user_id, listing_id, reason_code, report_id,
                    expires_at, created_at, updated_at
                ) values (?, ?, ?, null, ?, ?, ?)
                on duplicate key update
                    report_id = if(expires_at <= values(created_at), null, report_id),
                    created_at = if(expires_at <= values(created_at), values(created_at), created_at),
                    updated_at = if(expires_at <= values(created_at), values(updated_at), updated_at),
                    expires_at = if(expires_at <= values(created_at), values(expires_at), expires_at)
                """,
                reporterUserId,
                listingId,
                reasonCode,
                timestamp(expiresAt),
                timestamp(now),
                timestamp(now)) > 0;
    }

    Optional<DuplicateWindow> lockDuplicateWindow(String reporterUserId, String listingId, String reasonCode) {
        return jdbcTemplate.query("""
                select report_id, expires_at
                from listing_report_duplicate_windows
                where reporter_user_id = ? and listing_id = ? and reason_code = ?
                for update
                """,
                (resultSet, rowNum) -> new DuplicateWindow(
                        resultSet.getString("report_id"),
                        resultSet.getTimestamp("expires_at").toInstant()),
                reporterUserId,
                listingId,
                reasonCode).stream().findFirst();
    }

    void completeDuplicateWindow(
            String reporterUserId,
            String listingId,
            String reasonCode,
            String reportId,
            Instant now) {
        int updated = jdbcTemplate.update("""
                update listing_report_duplicate_windows
                set report_id = ?, updated_at = ?
                where reporter_user_id = ? and listing_id = ? and reason_code = ? and report_id is null
                """, reportId, timestamp(now), reporterUserId, listingId, reasonCode);
        if (updated != 1) {
            throw new ListingReportUnavailableException();
        }
    }

    // Locks the authoritative public listing row so visibility cannot change halfway through snapshot creation.
    Optional<PublicSubject> lockPublicSubject(String listingId) {
        return jdbcTemplate.query("""
                select id, version, seller_type, individual_seller_user_id, business_id,
                       category_id, title, description, condition_code, condition_notes,
                       price_amount, currency, negotiable, quantity, public_city, public_region,
                       publication_source, coalesce(published_at, updated_at) as published_at
                from listings
                where id = ?
                  and (
                    (seller_type = 'INDIVIDUAL' and status = 'ACTIVE' and moderation_status = 'APPROVED')
                    or
                    (seller_type = 'BUSINESS' and status = 'ACTIVE'
                        and publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                for share
                """,
                (resultSet, rowNum) -> new PublicSubject(
                        resultSet.getString("id"),
                        resultSet.getLong("version"),
                        resultSet.getString("seller_type"),
                        resultSet.getString("individual_seller_user_id"),
                        resultSet.getString("business_id"),
                        resultSet.getString("category_id"),
                        resultSet.getString("title"),
                        resultSet.getString("description"),
                        resultSet.getString("condition_code"),
                        resultSet.getString("condition_notes"),
                        resultSet.getBigDecimal("price_amount"),
                        resultSet.getString("currency"),
                        resultSet.getBoolean("negotiable"),
                        resultSet.getInt("quantity"),
                        resultSet.getString("public_city"),
                        resultSet.getString("public_region"),
                        resultSet.getString("publication_source"),
                        resultSet.getTimestamp("published_at").toInstant()),
                listingId).stream().findFirst();
    }

    List<PublicMedia> findPublicMedia(String listingId) {
        return jdbcTemplate.query("""
                select li.id as listing_image_id, mo.id as media_object_id, li.display_order,
                       mo.content_type, mo.size_bytes, mo.checksum_sha256
                from listing_images li
                join listing_media_objects mo on mo.id = li.media_object_id
                join listings l on l.id = li.listing_id
                where li.listing_id = ?
                  and l.status = 'ACTIVE'
                  and (
                    (l.seller_type = 'INDIVIDUAL'
                        and l.moderation_status = 'APPROVED'
                        and li.moderation_status = 'APPROVED'
                        and mo.moderation_status = 'APPROVED')
                    or
                    (l.seller_type = 'BUSINESS'
                        and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                  and mo.upload_status = 'UPLOADED'
                order by li.display_order, li.id
                """,
                (resultSet, rowNum) -> new PublicMedia(
                        resultSet.getString("listing_image_id"),
                        resultSet.getString("media_object_id"),
                        resultSet.getInt("display_order"),
                        resultSet.getString("content_type"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getString("checksum_sha256")),
                listingId);
    }

    void insertSnapshot(
            String snapshotId,
            PublicSubject subject,
            String policyVersion,
            String snapshotJson,
            String contentHash,
            List<ListingReportCanonicalizer.HashedMedia> media,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_report_subject_snapshots (
                    id, listing_id, listing_version, seller_type,
                    individual_seller_user_id, business_id, policy_version,
                    public_snapshot_json, content_hash, captured_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                snapshotId,
                subject.listingId(),
                subject.listingVersion(),
                subject.sellerType(),
                subject.individualSellerUserId(),
                subject.businessId(),
                policyVersion,
                snapshotJson,
                contentHash,
                timestamp(now));
        for (ListingReportCanonicalizer.HashedMedia item : media) {
            PublicMedia publicMedia = item.media();
            jdbcTemplate.update("""
                    insert into listing_report_subject_media_snapshots (
                        subject_snapshot_id, listing_image_id, media_object_id, display_order,
                        content_type, size_bytes, source_checksum_sha256, snapshot_hash
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshotId,
                    publicMedia.listingImageId(),
                    publicMedia.mediaObjectId(),
                    publicMedia.displayOrder(),
                    publicMedia.contentType(),
                    publicMedia.sizeBytes(),
                    publicMedia.sourceChecksumSha256(),
                    item.hash());
        }
    }

    String publicSnapshotJson(PublicSubject subject, List<ListingReportCanonicalizer.HashedMedia> media) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "LISTING_REPORT_SUBJECT_V1");
        snapshot.put("listingId", subject.listingId());
        snapshot.put("listingVersion", subject.listingVersion());
        snapshot.put("sellerType", subject.sellerType());
        snapshot.put("categoryId", subject.categoryId());
        snapshot.put("title", subject.title());
        snapshot.put("description", subject.description());
        snapshot.put("condition", subject.conditionCode());
        snapshot.put("conditionNotes", subject.conditionNotes());
        snapshot.put("priceAmount", subject.priceAmount());
        snapshot.put("currency", subject.currency());
        snapshot.put("negotiable", subject.negotiable());
        snapshot.put("quantity", subject.quantity());
        snapshot.put("publicCity", subject.publicCity());
        snapshot.put("publicRegion", subject.publicRegion());
        snapshot.put("publicationSource", subject.publicationSource());
        snapshot.put("publishedAt", subject.publishedAt());
        snapshot.put("media", media.stream().map(item -> Map.of(
                "listingImageId", item.media().listingImageId(),
                "displayOrder", item.media().displayOrder(),
                "contentType", item.media().contentType(),
                "sizeBytes", item.media().sizeBytes(),
                "snapshotHash", item.hash())).toList());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new ListingReportUnavailableException();
        }
    }

    // Aggregates only LISTING_REPORT work and leaves LISTING_REVIEW identities untouched.
    String ensureOpenReportCase(
            String proposedCaseId,
            PublicSubject subject,
            String reporterUserId,
            ListingReportReason reason,
            Instant now) {
        jdbcTemplate.update("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, subject_business_id,
                    submitted_by_user_id, status, priority, routing_queue,
                    assigned_admin_user_id, version, created_at, updated_at, resolved_at
                ) values (?, 'LISTING_REPORT', ?, ?, ?, ?, ?, 'OPEN', ?, ?, null, 0, ?, ?, null)
                on duplicate key update
                    priority = case
                        when values(priority) = 'HIGH' then 'HIGH'
                        when values(priority) = 'NORMAL' and priority = 'LOW' then 'NORMAL'
                        else priority
                    end,
                    updated_at = values(updated_at)
                """,
                proposedCaseId,
                subject.listingId(),
                subject.sellerType(),
                subject.individualSellerUserId(),
                subject.businessId(),
                reporterUserId,
                reason.priority(),
                reason.routingQueue(),
                timestamp(now),
                timestamp(now));
        return jdbcTemplate.queryForObject("""
                select id from moderation_cases
                where case_type = 'LISTING_REPORT'
                  and subject_listing_id = ?
                  and routing_queue = ?
                  and status in ('OPEN', 'CLAIMED')
                """, String.class, subject.listingId(), reason.routingQueue());
    }

    void insertReport(
            String reportId,
            String reporterUserId,
            ListingReportCanonicalizer.NormalizedRequest request,
            String policyVersion,
            String snapshotId,
            String caseId,
            Instant contentExpiresAt,
            Instant metadataExpiresAt,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_reports (
                    id, reporter_user_id, listing_id, reason_code, routing_queue,
                    priority, policy_version, status, subject_snapshot_id,
                    moderation_case_id, request_hash, content_expires_at,
                    metadata_expires_at, legal_review_required, legal_hold,
                    created_at, updated_at, resolved_at
                ) values (?, ?, ?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?, ?, ?, true, false, ?, ?, null)
                """,
                reportId,
                reporterUserId,
                request.listingId(),
                request.reason().name(),
                request.reason().routingQueue(),
                request.reason().priority(),
                policyVersion,
                snapshotId,
                caseId,
                request.requestHash(),
                timestamp(contentExpiresAt),
                timestamp(metadataExpiresAt),
                timestamp(now),
                timestamp(now));
    }

    void insertEvidence(
            String reportId,
            String statement,
            List<String> selectedMediaIds,
            List<ListingReportCanonicalizer.HashedMedia> media,
            ListingReportCanonicalizer canonicalizer,
            java.util.function.Supplier<String> idSupplier,
            Instant now) {
        int ordinal = 0;
        if (statement != null) {
            jdbcTemplate.update("""
                    insert into listing_report_evidence (
                        id, report_id, evidence_type, statement_text, listing_image_id,
                        media_object_id, content_type, size_bytes, evidence_hash, ordinal, created_at
                    ) values (?, ?, 'STATEMENT', ?, null, null, null, null, ?, ?, ?)
                    """,
                    idSupplier.get(),
                    reportId,
                    statement,
                    canonicalizer.statementHash(statement),
                    ordinal++,
                    timestamp(now));
        }
        Map<String, ListingReportCanonicalizer.HashedMedia> byImageId = media.stream()
                .collect(java.util.stream.Collectors.toMap(item -> item.media().listingImageId(), item -> item));
        for (String mediaId : selectedMediaIds) {
            ListingReportCanonicalizer.HashedMedia item = byImageId.get(mediaId);
            if (item == null) {
                throw new ListingReportNotFoundException();
            }
            PublicMedia publicMedia = item.media();
            jdbcTemplate.update("""
                    insert into listing_report_evidence (
                        id, report_id, evidence_type, statement_text, listing_image_id,
                        media_object_id, content_type, size_bytes, evidence_hash, ordinal, created_at
                    ) values (?, ?, 'LISTING_MEDIA_REF', null, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    idSupplier.get(),
                    reportId,
                    publicMedia.listingImageId(),
                    publicMedia.mediaObjectId(),
                    publicMedia.contentType(),
                    publicMedia.sizeBytes(),
                    item.hash(),
                    ordinal++,
                    timestamp(now));
        }
    }

    void insertHistory(
            String historyId,
            String reportId,
            String reasonCode,
            String policyVersion,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_report_history (
                    id, report_id, event_type, from_status, to_status,
                    actor_type, reason_code, policy_version, occurred_at
                ) values (?, ?, 'RECEIVED', null, 'RECEIVED', 'REPORTER', ?, ?, ?)
                """, historyId, reportId, reasonCode, policyVersion, timestamp(now));
    }

    void insertReceivedOutbox(
            String eventId,
            String topic,
            String reportId,
            String caseId,
            PublicSubject subject,
            String snapshotId,
            ListingReportReason reason,
            String policyVersion,
            String correlationId,
            String payloadJson,
            Instant now) {
        jdbcTemplate.update("""
                insert into outbox_events (
                    event_id, topic, message_key, aggregate_type, aggregate_id,
                    event_type, event_version, producer, occurred_at, correlation_id,
                    payload_json, deduplication_key, published_at, retry_count,
                    next_attempt_at, claim_token, claim_expires_at, last_error_code, created_at
                ) values (?, ?, ?, 'LISTING_REPORT', ?, 'listing-report.received.v1', 1,
                    'product-service', ?, ?, ?, ?, null, 0, ?, null, null, null, ?)
                """,
                eventId,
                topic,
                subject.listingId(),
                reportId,
                timestamp(now),
                correlationId,
                payloadJson,
                "listing-report.received.v1:" + reportId,
                timestamp(now),
                timestamp(now));
    }

    String receivedEventJson(
            String reportId,
            String caseId,
            PublicSubject subject,
            String snapshotId,
            ListingReportReason reason,
            String policyVersion) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "LISTING_REPORT_RECEIVED_V1");
        event.put("reportId", reportId);
        event.put("caseId", caseId);
        event.put("listingId", subject.listingId());
        event.put("listingVersion", subject.listingVersion());
        event.put("subjectSnapshotId", snapshotId);
        event.put("reasonCode", reason.name());
        event.put("routingQueue", reason.routingQueue());
        event.put("priority", reason.priority());
        event.put("policyVersion", policyVersion);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new ListingReportUnavailableException();
        }
    }

    Optional<ListingReportResponse> findOwnedReport(String reportId, String reporterUserId) {
        return jdbcTemplate.query("""
                select id, status, listing_id, reason_code, policy_version, created_at, updated_at
                from listing_reports
                where id = ? and reporter_user_id = ?
                """, this::reportResponse, reportId, reporterUserId).stream().findFirst();
    }

    void incrementRateBucket(
            String abuseKeyHash,
            String bucketType,
            Instant bucketStart,
            Instant bucketEnd,
            Instant expiresAt,
            int limit,
            Instant now) {
        jdbcTemplate.update("""
                insert into listing_report_rate_limit_buckets (
                    abuse_key_hash, bucket_type, bucket_start, bucket_end,
                    accepted_count, expires_at, updated_at
                ) values (?, ?, ?, ?, 0, ?, ?)
                on duplicate key update abuse_key_hash = values(abuse_key_hash)
                """,
                abuseKeyHash,
                bucketType,
                timestamp(bucketStart),
                timestamp(bucketEnd),
                timestamp(expiresAt),
                timestamp(now));
        Integer count = jdbcTemplate.queryForObject("""
                select accepted_count from listing_report_rate_limit_buckets
                where abuse_key_hash = ? and bucket_type = ? and bucket_start = ?
                for update
                """, Integer.class, abuseKeyHash, bucketType, timestamp(bucketStart));
        if (count == null || count >= limit) {
            throw new ListingReportRateLimitException(bucketEnd.getEpochSecond() - now.getEpochSecond());
        }
        jdbcTemplate.update("""
                update listing_report_rate_limit_buckets
                set accepted_count = accepted_count + 1, updated_at = ?
                where abuse_key_hash = ? and bucket_type = ? and bucket_start = ?
                """, timestamp(now), abuseKeyHash, bucketType, timestamp(bucketStart));
    }

    private ListingReportResponse reportResponse(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ListingReportResponse(
                resultSet.getString("id"),
                resultSet.getString("status"),
                resultSet.getString("listing_id"),
                resultSet.getString("reason_code"),
                resultSet.getString("policy_version"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    record IdempotencyReservation(String requestHash, String reportId, Instant expiresAt) {
    }

    record DuplicateWindow(String reportId, Instant expiresAt) {
    }

    record PublicSubject(
            String listingId,
            long listingVersion,
            String sellerType,
            String individualSellerUserId,
            String businessId,
            String categoryId,
            String title,
            String description,
            String conditionCode,
            String conditionNotes,
            BigDecimal priceAmount,
            String currency,
            boolean negotiable,
            int quantity,
            String publicCity,
            String publicRegion,
            String publicationSource,
            Instant publishedAt
    ) {
    }

    record PublicMedia(
            String listingImageId,
            String mediaObjectId,
            int displayOrder,
            String contentType,
            long sizeBytes,
            String sourceChecksumSha256
    ) {
    }
}
