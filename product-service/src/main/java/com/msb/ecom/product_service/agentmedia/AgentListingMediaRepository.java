package com.msb.ecom.product_service.agentmedia;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class AgentListingMediaRepository {

    private final JdbcTemplate jdbcTemplate;

    public Optional<ListingSnapshot> findListing(String listingId) {
        List<ListingSnapshot> matches = jdbcTemplate.query("""
                        select id, seller_type, individual_seller_user_id, status, version
                        from listings
                        where id = ?
                        """,
                (resultSet, rowNumber) -> new ListingSnapshot(
                        resultSet.getString("id"),
                        resultSet.getString("seller_type"),
                        resultSet.getString("individual_seller_user_id"),
                        resultSet.getString("status"),
                        resultSet.getLong("version")),
                listingId);
        return matches.stream().findFirst();
    }

    public Optional<MediaSnapshot> findMedia(String listingId, String mediaId) {
        List<MediaSnapshot> matches = jdbcTemplate.query("""
                        select id, listing_id, seller_type, individual_seller_user_id,
                               object_key, content_type, size_bytes, checksum_sha256,
                               upload_status, moderation_status, version
                        from listing_media_objects
                        where listing_id = ? and id = ?
                        """,
                (resultSet, rowNumber) -> new MediaSnapshot(
                        resultSet.getString("id"),
                        resultSet.getString("listing_id"),
                        resultSet.getString("seller_type"),
                        resultSet.getString("individual_seller_user_id"),
                        resultSet.getString("object_key"),
                        resultSet.getString("content_type"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getString("checksum_sha256"),
                        resultSet.getString("upload_status"),
                        resultSet.getString("moderation_status"),
                        resultSet.getLong("version")),
                listingId,
                mediaId);
        return matches.stream().findFirst();
    }

    public record ListingSnapshot(
            String id,
            String sellerType,
            String individualSellerUserId,
            String status,
            long version
    ) {
    }

    public record MediaSnapshot(
            String id,
            String listingId,
            String sellerType,
            String individualSellerUserId,
            String objectKey,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            String uploadStatus,
            String moderationStatus,
            long version
    ) {
    }
}
