package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingSellerType;
import com.msb.ecom.product_service.dto.ListingDraftResponse;
import com.msb.ecom.product_service.dto.PublicListingResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.id = ?
                  and l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                """,
                (rs, rowNum) -> publicListingResponse(rs),
                listingId);
        return matches.stream().findFirst();
    }

    public List<PublicListingResponse> findPublicListings(int limit) {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
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
                (rs, rowNum) -> publicListingResponse(rs),
                limit);
    }

    public List<PublicListingResponse> findPublicListingsBySellerType(String sellerType, int limit) {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                  and l.seller_type = ?
                order by l.published_at desc, l.updated_at desc, l.id desc
                limit ?
                """,
                (rs, rowNum) -> publicListingResponse(rs),
                sellerType,
                limit);
    }

    public List<PublicListingResponse> searchPublicListingsBySellerType(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        StringBuilder sql = new StringBuilder("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                  and l.seller_type = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(sellerType);

        if (hasText(criteria.keyword())) {
            String keyword = "%" + escapedLike(criteria.keyword().toLowerCase(Locale.ROOT)) + "%";
            sql.append("""
                    and (
                         lower(l.title) like ? escape '!'
                      or lower(l.description) like ? escape '!'
                      or lower(c.name) like ? escape '!'
                      or lower(c.slug) like ? escape '!'
                      or lower(l.condition_code) like ? escape '!'
                      or lower(l.public_city) like ? escape '!'
                      or lower(l.public_region) like ? escape '!'
                    )
                    """);
            for (int index = 0; index < 7; index++) {
                parameters.add(keyword);
            }
        }
        if (hasText(criteria.categoryId())) {
            sql.append(" and l.category_id = ?\n");
            parameters.add(criteria.categoryId());
        }
        if (hasText(criteria.condition())) {
            sql.append(" and l.condition_code = ?\n");
            parameters.add(criteria.condition());
        }
        if (criteria.minPrice() != null) {
            sql.append(" and l.price_amount >= ?\n");
            parameters.add(criteria.minPrice());
        }
        if (criteria.maxPrice() != null) {
            sql.append(" and l.price_amount <= ?\n");
            parameters.add(criteria.maxPrice());
        }
        if (hasText(criteria.city())) {
            sql.append(" and lower(l.public_city) = ?\n");
            parameters.add(criteria.city().toLowerCase(Locale.ROOT));
        }
        if (hasText(criteria.county())) {
            sql.append(" and lower(l.public_region) = ?\n");
            parameters.add(criteria.county().toLowerCase(Locale.ROOT));
        }
        appendCursorPredicate(sql, parameters, criteria);

        sql.append(searchOrderBy(criteria.sort()));
        sql.append(" limit ?");
        parameters.add(limit);

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> publicListingResponse(rs), parameters.toArray());
    }

    public List<PublicListingResponse> findPublicListingsByIds(List<String> listingIds) {
        if (listingIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", listingIds.stream().map(id -> "?").toList());
        List<PublicListingResponse> listings = jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                  and l.id in (%s)
                """.formatted(placeholders),
                (rs, rowNum) -> publicListingResponse(rs),
                listingIds.toArray());
        Map<String, PublicListingResponse> byId = new HashMap<>();
        for (PublicListingResponse listing : listings) {
            byId.put(listing.id(), listing);
        }
        return listingIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public List<PublicListingResponse> findAllPublicListingsForSearchIndex() {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at
                from listings l
                join categories c on c.id = l.category_id
                where l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                order by coalesce(l.published_at, l.updated_at) desc, l.id desc
                """,
                (rs, rowNum) -> publicListingResponse(rs));
    }

    public long countPendingReview() {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from listings
                where status = 'PENDING_REVIEW'
                  and moderation_status = 'PENDING'
                """, Long.class);
        return count == null ? 0 : count;
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
                    status = 'DRAFT',
                    moderation_status = 'NOT_SUBMITTED',
                    published_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status in ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'CLOSED')
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

    public int closeListing(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'CLOSED',
                    published_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status in ('DRAFT', 'PENDING_REVIEW', 'ACTIVE')
                  and version = ?
                """,
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public int updateActiveListingByAdmin(String listingId, long expectedVersion, ListingDraftUpdate update, Instant now) {
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
                  and status = 'ACTIVE'
                  and moderation_status = 'APPROVED'
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

    public int removeActiveListingByAdmin(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'REMOVED_BY_ADMIN',
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status = 'ACTIVE'
                  and moderation_status = 'APPROVED'
                  and version = ?
                """,
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public int markSellerEdited(String listingId, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'DRAFT',
                    moderation_status = 'NOT_SUBMITTED',
                    published_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status in ('PENDING_REVIEW', 'ACTIVE', 'CLOSED')
                """,
                Timestamp.from(now),
                listingId);
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
                null,
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

    // Builds the safe public listing projection shared by public browse/search contracts.
    private PublicListingResponse publicListingResponse(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PublicListingResponse(
                rs.getString("id"),
                rs.getString("seller_type"),
                sellerId(rs),
                null,
                null,
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
                List.of());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String escapedLike(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    private String searchOrderBy(String sort) {
        return switch (sort) {
            case "price_asc" -> " order by l.price_amount asc, coalesce(l.published_at, l.updated_at) desc, l.id desc\n";
            case "price_desc" -> " order by l.price_amount desc, coalesce(l.published_at, l.updated_at) desc, l.id desc\n";
            default -> " order by coalesce(l.published_at, l.updated_at) desc, l.id desc\n";
        };
    }

    private void appendCursorPredicate(StringBuilder sql, List<Object> parameters, PublicListingSearchCriteria criteria) {
        if (!hasText(criteria.cursorListingId()) || criteria.cursorPublishedAt() == null) {
            return;
        }
        Timestamp cursorPublishedAt = Timestamp.from(criteria.cursorPublishedAt());
        if ("price_asc".equals(criteria.sort()) || "price_desc".equals(criteria.sort())) {
            if (criteria.cursorPrice() == null) {
                return;
            }
            String priceOperator = "price_asc".equals(criteria.sort()) ? ">" : "<";
            sql.append("""
                    and (
                         l.price_amount %s ?
                      or (l.price_amount = ? and coalesce(l.published_at, l.updated_at) < ?)
                      or (l.price_amount = ? and coalesce(l.published_at, l.updated_at) = ? and l.id < ?)
                    )
                    """.formatted(priceOperator));
            parameters.add(criteria.cursorPrice());
            parameters.add(criteria.cursorPrice());
            parameters.add(cursorPublishedAt);
            parameters.add(criteria.cursorPrice());
            parameters.add(cursorPublishedAt);
            parameters.add(criteria.cursorListingId());
            return;
        }
        sql.append("""
                and (
                     coalesce(l.published_at, l.updated_at) < ?
                  or (coalesce(l.published_at, l.updated_at) = ? and l.id < ?)
                )
                """);
        parameters.add(cursorPublishedAt);
        parameters.add(cursorPublishedAt);
        parameters.add(criteria.cursorListingId());
    }

    private String sellerId(java.sql.ResultSet rs) throws java.sql.SQLException {
        return "BUSINESS".equals(rs.getString("seller_type"))
                ? rs.getString("business_id")
                : rs.getString("individual_seller_user_id");
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
