package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingSellerType;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ListingDraftRepository {

    private final JdbcTemplate jdbcTemplate;

    public ListingDraftResponse insertDraft(ListingDraftInsert draft) {
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, title, description, condition_code, condition_notes,
                    price_amount, currency, negotiable, sku, quantity, public_city, public_region,
                    status, moderation_status, version, created_at, updated_at
                )
                values (?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'DRAFT', 'NOT_SUBMITTED', 0, ?, ?)
                """,
                draft.id(),
                draft.sellerType().name(),
                draft.individualSellerUserId(),
                draft.businessId(),
                draft.categoryId(),
                draft.title(),
                draft.description(),
                draft.condition().name(),
                draft.conditionNotes(),
                draft.priceAmount(),
                draft.currency(),
                draft.negotiable(),
                draft.sku(),
                draft.quantity(),
                draft.publicCity(),
                draft.publicRegion(),
                Timestamp.from(draft.now()),
                Timestamp.from(draft.now()));

        return findById(draft.id());
    }

    public Optional<ListingOwnerSnapshot> findOwnerSnapshot(String listingId) {
        List<ListingOwnerSnapshot> matches = jdbcTemplate.query("""
                        select id, seller_type, individual_seller_user_id, business_id, status
                        from listings
                        where id = ?
                        """,
                (rs, rowNum) -> new ListingOwnerSnapshot(
                        rs.getString("id"),
                        ListingSellerType.valueOf(rs.getString("seller_type")),
                        rs.getString("individual_seller_user_id"),
                        rs.getString("business_id"),
                        rs.getString("status")),
                listingId);
        return matches.stream().findFirst();
    }

    public Optional<ListingDraftResponse> findOptionalById(String listingId) {
        List<ListingDraftResponse> matches = jdbcTemplate.query("""
                select id, seller_type, individual_seller_user_id, business_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, version, created_at, updated_at
                from listings
                where id = ?
                """,
                (rs, rowNum) -> listingDraftResponse(rs),
                listingId);
        return matches.stream().findFirst();
    }

    public List<ListingDraftResponse> findByIndividualSellerUserId(String userId) {
        return jdbcTemplate.query("""
                select id, seller_type, individual_seller_user_id, business_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, version, created_at, updated_at
                from listings
                where seller_type = 'INDIVIDUAL'
                  and individual_seller_user_id = ?
                order by updated_at desc, created_at desc
                """,
                (rs, rowNum) -> listingDraftResponse(rs),
                userId);
    }

    public List<ListingDraftResponse> findByBusinessId(String businessId) {
        return jdbcTemplate.query("""
                select id, seller_type, individual_seller_user_id, business_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, version, created_at, updated_at
                from listings
                where seller_type = 'BUSINESS'
                  and business_id = ?
                order by updated_at desc, created_at desc
                """,
                (rs, rowNum) -> listingDraftResponse(rs),
                businessId);
    }

    public List<ListingDraftResponse> findPendingReview() {
        return jdbcTemplate.query("""
                select id, seller_type, individual_seller_user_id, business_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, version, created_at, updated_at
                from listings
                where status = 'PENDING_REVIEW'
                  and moderation_status = 'PENDING'
                order by updated_at asc, created_at asc
                """,
                (rs, rowNum) -> listingDraftResponse(rs));
    }

    public Optional<PublicListingResponse> findPublicListingById(String listingId) {
        List<PublicListingResponse> matches = jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.id = ?
                  and l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                """,
                (rs, rowNum) -> new PublicListingResponse(
                        rs.getString("id"),
                        rs.getString("seller_type"),
                        rs.getString("category_id"),
                        rs.getString("category_slug"),
                        rs.getString("category_name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("condition_code"),
                        rs.getString("condition_notes"),
                        rs.getBigDecimal("price_amount"),
                        rs.getString("currency"),
                        rs.getBoolean("negotiable"),
                        rs.getInt("quantity"),
                        rs.getString("public_city"),
                        rs.getString("public_region"),
                        rs.getTimestamp("published_at").toInstant(),
                        null,
                        List.of()),
                listingId);
        return matches.stream().findFirst();
    }

    public List<PublicListingResponse> findPublicListings(int limit) {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                order by l.published_at desc, l.updated_at desc, l.id desc
                limit ?
                """,
                (rs, rowNum) -> new PublicListingResponse(
                        rs.getString("id"),
                        rs.getString("seller_type"),
                        rs.getString("category_id"),
                        rs.getString("category_slug"),
                        rs.getString("category_name"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getString("condition_code"),
                        rs.getString("condition_notes"),
                        rs.getBigDecimal("price_amount"),
                        rs.getString("currency"),
                        rs.getBoolean("negotiable"),
                        rs.getInt("quantity"),
                        rs.getString("public_city"),
                        rs.getString("public_region"),
                        rs.getTimestamp("published_at").toInstant(),
                        null,
                        List.of()),
                limit);
    }

    public int updateDraft(String listingId, long expectedVersion, ListingDraftUpdate update, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set category_id = ?,
                    title = ?,
                    description = ?,
                    condition_code = ?,
                    condition_notes = ?,
                    price_amount = ?,
                    currency = ?,
                    negotiable = ?,
                    sku = ?,
                    quantity = ?,
                    public_city = ?,
                    public_region = ?,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status = 'DRAFT'
                  and version = ?
                """,
                update.categoryId(),
                update.title(),
                update.description(),
                update.condition().name(),
                update.conditionNotes(),
                update.priceAmount(),
                update.currency(),
                update.negotiable(),
                update.sku(),
                update.quantity(),
                update.publicCity(),
                update.publicRegion(),
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public int submitForReview(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'PENDING_REVIEW',
                    moderation_status = 'PENDING',
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status = 'DRAFT'
                  and version = ?
                """,
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public int applyModerationDecision(
            String listingId,
            long expectedVersion,
            String status,
            String moderationStatus,
            Instant publishedAt,
            Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = ?,
                    moderation_status = ?,
                    published_at = ?,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status = 'PENDING_REVIEW'
                  and moderation_status = 'PENDING'
                  and version = ?
                """,
                status,
                moderationStatus,
                publishedAt == null ? null : Timestamp.from(publishedAt),
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public ListingDraftResponse findById(String listingId) {
        return findOptionalById(listingId).orElseThrow();
    }

    private ListingDraftResponse listingDraftResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListingDraftResponse(
                rs.getString("id"),
                rs.getString("seller_type"),
                rs.getString("individual_seller_user_id"),
                rs.getString("business_id"),
                rs.getString("category_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("condition_code"),
                rs.getString("condition_notes"),
                rs.getBigDecimal("price_amount"),
                rs.getString("currency"),
                rs.getBoolean("negotiable"),
                rs.getString("sku"),
                rs.getInt("quantity"),
                rs.getString("public_city"),
                rs.getString("public_region"),
                rs.getString("status"),
                rs.getString("moderation_status"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                List.of());
    }

    public record ListingOwnerSnapshot(
            String id,
            ListingSellerType sellerType,
            String individualSellerUserId,
            String businessId,
            String status
    ) {
    }
}
