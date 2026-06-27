package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingMediaNotFoundException;
import com.msb.ecom.product_service.dto.ListingImageResponse;
import com.msb.ecom.product_service.dto.ListingMediaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ListingMediaRepository {

    private final JdbcTemplate jdbcTemplate;

    public ListingMediaResponse insertMedia(ListingMediaInsert media) {
        jdbcTemplate.update("""
                insert into listing_media_objects (
                    id, listing_id, seller_type, individual_seller_user_id, business_id,
                    object_bucket, object_key, original_file_name, content_type, size_bytes,
                    checksum_sha256, upload_status, moderation_status, version, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'PENDING_UPLOAD', 'NOT_SUBMITTED', 0, ?, ?)
                """,
                media.id(),
                media.listingId(),
                media.sellerType().name(),
                media.individualSellerUserId(),
                media.businessId(),
                media.objectBucket(),
                media.objectKey(),
                media.originalFileName(),
                media.contentType(),
                media.sizeBytes(),
                media.checksumSha256(),
                Timestamp.from(media.now()),
                Timestamp.from(media.now()));

        return findMediaById(media.listingId(), media.id()).orElseThrow(ListingMediaNotFoundException::new);
    }

    public Optional<ListingMediaResponse> findMediaById(String listingId, String mediaId) {
        List<ListingMediaResponse> matches = jdbcTemplate.query("""
                        select id, listing_id, seller_type, individual_seller_user_id, business_id,
                               object_bucket, object_key, original_file_name, content_type, size_bytes,
                               checksum_sha256, upload_status, moderation_status, version, created_at, updated_at
                        from listing_media_objects
                        where listing_id = ? and id = ?
                        """,
                (rs, rowNum) -> mediaResponse(rs),
                listingId,
                mediaId);
        return matches.stream().findFirst();
    }

    public ListingMediaResponse confirmMedia(
            String listingId,
            String mediaId,
            long sizeBytes,
            String checksumSha256,
            Timestamp now) {
        int updated = jdbcTemplate.update("""
                update listing_media_objects
                set size_bytes = ?,
                    checksum_sha256 = ?,
                    upload_status = 'UPLOADED',
                    version = version + 1,
                    updated_at = ?
                where listing_id = ? and id = ? and upload_status = 'PENDING_UPLOAD'
                """,
                sizeBytes,
                checksumSha256,
                now,
                listingId,
                mediaId);
        if (updated == 0) {
            throw new ListingMediaNotFoundException();
        }
        return findMediaById(listingId, mediaId).orElseThrow(ListingMediaNotFoundException::new);
    }

    public void replaceImages(String listingId, List<ListingImageInsert> images) {
        jdbcTemplate.update("delete from listing_images where listing_id = ?", listingId);
        for (ListingImageInsert image : images) {
            jdbcTemplate.update("""
                    insert into listing_images (
                        id, listing_id, media_object_id, display_order, alt_text,
                        moderation_status, version, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, 'NOT_SUBMITTED', 0, ?, ?)
                    """,
                    image.id(),
                    image.listingId(),
                    image.mediaObjectId(),
                    image.displayOrder(),
                    image.altText(),
                    Timestamp.from(image.now()),
                    Timestamp.from(image.now()));
        }
    }

    public List<ListingImageResponse> findImagesByListingId(String listingId) {
        return jdbcTemplate.query("""
                        select li.id, li.listing_id, li.media_object_id, li.display_order,
                               li.alt_text, li.moderation_status, li.version, li.created_at, li.updated_at,
                               mo.original_file_name, mo.content_type, mo.size_bytes, mo.upload_status,
                               mo.object_bucket, mo.object_key
                        from listing_images li
                        join listing_media_objects mo on mo.id = li.media_object_id
                        where li.listing_id = ?
                        order by li.display_order
                        """,
                (rs, rowNum) -> imageResponse(rs),
                listingId);
    }

    public boolean hasAttachedUploadedImage(String listingId) {
        Integer count = jdbcTemplate.queryForObject("""
                        select count(*)
                        from listing_images li
                        join listing_media_objects mo on mo.id = li.media_object_id
                        where li.listing_id = ?
                          and mo.upload_status = 'UPLOADED'
                        """,
                Integer.class,
                listingId);
        return count != null && count > 0;
    }

    public void markImagesPendingReview(String listingId, Timestamp now) {
        jdbcTemplate.update("""
                update listing_images
                set moderation_status = 'PENDING',
                    version = version + 1,
                    updated_at = ?
                where listing_id = ?
                """,
                now,
                listingId);

        jdbcTemplate.update("""
                update listing_media_objects
                set moderation_status = 'PENDING',
                    version = version + 1,
                    updated_at = ?
                where listing_id = ?
                  and id in (
                    select media_object_id
                    from listing_images
                    where listing_id = ?
                  )
                """,
                now,
                listingId,
                listingId);
    }

    public void markImagesModerationStatus(String listingId, String moderationStatus, Timestamp now) {
        jdbcTemplate.update("""
                update listing_images
                set moderation_status = ?,
                    version = version + 1,
                    updated_at = ?
                where listing_id = ?
                """,
                moderationStatus,
                now,
                listingId);

        jdbcTemplate.update("""
                update listing_media_objects
                set moderation_status = ?,
                    version = version + 1,
                    updated_at = ?
                where listing_id = ?
                  and id in (
                    select media_object_id
                    from listing_images
                    where listing_id = ?
                  )
                """,
                moderationStatus,
                now,
                listingId,
                listingId);
    }

    private ListingMediaResponse mediaResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListingMediaResponse(
                rs.getString("id"),
                rs.getString("listing_id"),
                rs.getString("seller_type"),
                rs.getString("individual_seller_user_id"),
                rs.getString("business_id"),
                rs.getString("object_bucket"),
                rs.getString("object_key"),
                rs.getString("original_file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getString("upload_status"),
                rs.getString("moderation_status"),
                "LOCAL_DEMO",
                "local-demo://" + rs.getString("object_bucket") + "/" + rs.getString("object_key"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private ListingImageResponse imageResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListingImageResponse(
                rs.getString("id"),
                rs.getString("listing_id"),
                rs.getString("media_object_id"),
                rs.getInt("display_order"),
                rs.getString("alt_text"),
                rs.getString("moderation_status"),
                rs.getString("original_file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("upload_status"),
                rs.getString("object_bucket"),
                rs.getString("object_key"),
                "local-demo://" + rs.getString("object_bucket") + "/" + rs.getString("object_key"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
