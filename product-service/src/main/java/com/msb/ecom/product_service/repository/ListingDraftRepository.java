package com.msb.ecom.product_service.repository;

import com.msb.ecom.product_service.model.ListingSellerType;
import com.msb.ecom.product_service.dto.ChatListingEligibilityResponse;
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
import java.util.Set;

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
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        'DRAFT', 'NOT_SUBMITTED', 0, ?, ?)
                """,
                draft.id(),
                draft.sellerType().name(),
                draft.individualSellerUserId(),
                draft.businessId(),
                draft.storeId(),
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
                select id, seller_type, individual_seller_user_id, business_id, store_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, publication_source, published_at, version, created_at, updated_at
                from listings
                where id = ?
                """,
                (rs, rowNum) -> listingDraftResponse(rs),
                listingId);
        return matches.stream().findFirst();
    }

    public List<ListingDraftResponse> findByIndividualSellerUserId(String userId) {
        return jdbcTemplate.query("""
                select id, seller_type, individual_seller_user_id, business_id, store_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, publication_source, published_at, version, created_at, updated_at
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
                select id, seller_type, individual_seller_user_id, business_id, store_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, publication_source, published_at, version, created_at, updated_at
                from listings
                where seller_type = 'BUSINESS'
                  and business_id = ?
                order by updated_at desc, created_at desc
                """,
                (rs, rowNum) -> listingDraftResponse(rs),
                businessId);
    }

    // Searches one business catalog with a deterministic cursor for the seller management screen.
    public List<ListingDraftResponse> searchBusinessStoreItems(
            String businessId,
            BusinessStoreItemSearchCriteria criteria,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                select id, seller_type, individual_seller_user_id, business_id, store_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, publication_source, published_at, version, created_at, updated_at
                from listings
                where seller_type = 'BUSINESS'
                  and business_id = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(businessId);

        if (hasText(criteria.keyword())) {
            String keyword = "%" + escapedLike(criteria.keyword().toLowerCase(Locale.ROOT)) + "%";
            sql.append("""
                    and (
                         lower(title) like ? escape '!'
                      or lower(sku) like ? escape '!'
                    )
                    """);
            parameters.add(keyword);
            parameters.add(keyword);
        }
        if (hasText(criteria.status())) {
            sql.append(" and status = ?\n");
            parameters.add(criteria.status());
        }
        if (criteria.cursorUpdatedAt() != null && hasText(criteria.cursorListingId())) {
            Timestamp cursorUpdatedAt = Timestamp.from(criteria.cursorUpdatedAt());
            sql.append("""
                    and (
                         updated_at < ?
                      or (updated_at = ? and id < ?)
                    )
                    """);
            parameters.add(cursorUpdatedAt);
            parameters.add(cursorUpdatedAt);
            parameters.add(criteria.cursorListingId());
        }

        sql.append(" order by updated_at desc, id desc\n");
        sql.append(" limit ?");
        parameters.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> listingDraftResponse(rs), parameters.toArray());
    }

    // Counts lifecycle states across the full business catalog independently of the selected list filter.
    public BusinessStoreItemStatusCounts countBusinessStoreItemStatuses(String businessId) {
        return jdbcTemplate.queryForObject("""
                select count(*) as total_count,
                       sum(case when status = 'DRAFT' then 1 else 0 end) as draft_count,
                       sum(case when status = 'ACTIVE' then 1 else 0 end) as active_count,
                       sum(case when status = 'PAUSED' then 1 else 0 end) as paused_count,
                       sum(case when status = 'REMOVED_BY_ADMIN' then 1 else 0 end) as removed_count
                from listings
                where seller_type = 'BUSINESS'
                  and business_id = ?
                """,
                (rs, rowNum) -> new BusinessStoreItemStatusCounts(
                        rs.getLong("total_count"),
                        rs.getLong("draft_count"),
                        rs.getLong("active_count"),
                        rs.getLong("paused_count"),
                        rs.getLong("removed_count")),
                businessId);
    }

    public List<ListingDraftResponse> findPendingReview() {
        return jdbcTemplate.query("""
                select id, seller_type, individual_seller_user_id, business_id, store_id, category_id,
                       title, description, condition_code, condition_notes, price_amount,
                       currency, negotiable, sku, quantity, public_city, public_region,
                       status, moderation_status, publication_source, published_at, version, created_at, updated_at
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
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listings l
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where l.id = ?
                  and (
                    (l.seller_type = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (l.seller_type = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                """,
                (rs, rowNum) -> publicListingResponse(rs),
                listingId);
        return matches.stream().findFirst();
    }

    public Optional<ChatListingEligibilityResponse> findChatListingEligibility(String listingId) {
        List<ChatListingEligibilityResponse> matches = jdbcTemplate.query("""
                select l.id, l.seller_type, l.individual_seller_user_id, l.quantity,
                       l.title, l.public_city, l.public_region
                from listings l
                where l.id = ?
                  and l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                """,
                (rs, rowNum) -> new ChatListingEligibilityResponse(
                        rs.getString("id"),
                        "INDIVIDUAL".equals(rs.getString("seller_type")),
                        rs.getString("seller_type"),
                        rs.getString("individual_seller_user_id"),
                        rs.getInt("quantity"),
                        rs.getString("title"),
                        rs.getString("public_city"),
                        rs.getString("public_region"),
                        null,
                        null),
                listingId);
        return matches.stream().findFirst();
    }

    public List<PublicListingResponse> findPublicListings(int limit) {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listings l
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where (
                    (l.seller_type = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (l.seller_type = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
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
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listings l
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where l.seller_type = ?
                  and (
                    (? = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (? = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                order by l.published_at desc, l.updated_at desc, l.id desc
                limit ?
                """,
                (rs, rowNum) -> publicListingResponse(rs),
                sellerType,
                sellerType,
                sellerType,
                limit);
    }

    public List<PublicListingResponse> searchPublicListingsBySellerType(String sellerType, PublicListingSearchCriteria criteria, int limit) {
        StringBuilder sql = new StringBuilder("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listings l
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where l.seller_type = ?
                  and (
                    (? = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (? = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(sellerType);
        parameters.add(sellerType);
        parameters.add(sellerType);

        if ("BUSINESS".equals(sellerType)) {
            if (criteria.visibleBusinessIds() == null || criteria.visibleBusinessIds().isEmpty()) {
                sql.append(" and 1 = 0\n");
            } else {
                sql.append(" and l.business_id in (%s)\n"
                        .formatted(placeholders(criteria.visibleBusinessIds().size())));
                parameters.addAll(criteria.visibleBusinessIds());
            }
        }

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
                      or (? = 'BUSINESS' and lower(l.sku) like ? escape '!')
                    """);
            for (int index = 0; index < 7; index++) {
                parameters.add(keyword);
            }
            parameters.add(sellerType);
            parameters.add(keyword);
            if ("BUSINESS".equals(sellerType) && criteria.businessIds() != null && !criteria.businessIds().isEmpty()) {
                sql.append("""
                      or (? = 'BUSINESS' and l.business_id in (%s))
                """.formatted(placeholders(criteria.businessIds().size())));
                parameters.add(sellerType);
                parameters.addAll(criteria.businessIds());
            }
            sql.append("""
                    )
                    """);
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

    // Supplies bounded business candidates so auth-service can enforce active business/store visibility before paging.
    public Set<String> findSelfPublishedBusinessIds(int limit) {
        return new java.util.LinkedHashSet<>(jdbcTemplate.queryForList("""
                select distinct business_id
                from listings
                where seller_type = 'BUSINESS'
                  and status = 'ACTIVE'
                  and publication_source = 'BUSINESS_SELF_PUBLISHED'
                  and business_id is not null
                order by business_id
                limit ?
                """, String.class, limit));
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
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listings l
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where (
                    (l.seller_type = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (l.seller_type = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
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

    public List<PublicListingResponse> findPublicLikedListingsForUser(String userId, int limit) {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listing_likes lk
                join listings l on l.id = lk.listing_id
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where lk.user_id = ?
                  and lk.active = true
                  and (
                    (l.seller_type = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (l.seller_type = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                order by lk.updated_at desc, coalesce(l.published_at, l.updated_at) desc, l.id desc
                limit ?
                """,
                (rs, rowNum) -> publicListingResponse(rs),
                userId,
                limit);
    }

    public List<PublicListingResponse> findAllPublicListingsForSearchIndex() {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.category_id, c.slug as category_slug, c.name as category_name,
                       l.individual_seller_user_id, l.business_id,
                       l.title, l.description, l.condition_code, l.condition_notes, l.price_amount,
                       l.currency, l.negotiable, l.quantity, l.public_city, l.public_region,
                       coalesce(l.published_at, l.updated_at) as published_at,
                       coalesce(es.visit_count, 0) as visit_count,
                       coalesce(es.like_count, 0) as like_count
                from listings l
                join categories c on c.id = l.category_id
                left join listing_engagement_stats es on es.listing_id = l.id
                where (
                    (l.seller_type = 'INDIVIDUAL' and l.status = 'ACTIVE' and l.moderation_status = 'APPROVED')
                    or (l.seller_type = 'BUSINESS' and l.status = 'ACTIVE' and l.publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
                order by coalesce(l.published_at, l.updated_at) desc, l.id desc
                """,
                (rs, rowNum) -> publicListingResponse(rs));
    }

    // Finds authoritative active individual listings whose current version still needs an immutable source snapshot.
    public List<ListingDraftResponse> findActiveIndividualsMissingKnowledgeVersion(int limit) {
        return jdbcTemplate.query("""
                select l.id, l.seller_type, l.individual_seller_user_id, l.business_id, l.store_id,
                       l.category_id, l.title, l.description, l.condition_code, l.condition_notes,
                       l.price_amount, l.currency, l.negotiable, l.sku, l.quantity,
                       l.public_city, l.public_region, l.status, l.moderation_status,
                       l.publication_source, l.published_at, l.version, l.created_at, l.updated_at
                from listings l
                where l.seller_type = 'INDIVIDUAL'
                  and l.status = 'ACTIVE'
                  and l.moderation_status = 'APPROVED'
                  and not exists (
                    select 1
                    from listing_knowledge_versions kv
                    where kv.listing_id = l.id
                      and kv.source_version = l.version
                  )
                order by l.id asc
                limit ?
                """,
                (rs, rowNum) -> listingDraftResponse(rs),
                limit);
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
                    publication_source = null,
                    published_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status in ('DRAFT', 'PENDING_REVIEW', 'ACTIVE', 'CLOSED', 'CHANGES_REQUESTED')
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
                    publication_source = null,
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

    public int closeActiveIndividualListingFromChat(String listingId, String sellerUserId, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'CLOSED',
                    publication_source = null,
                    published_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and seller_type = 'INDIVIDUAL'
                  and individual_seller_user_id = ?
                  and status = 'ACTIVE'
                  and moderation_status = 'APPROVED'
                """,
                Timestamp.from(now),
                listingId,
                sellerUserId);
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
                  and (
                    (seller_type = 'INDIVIDUAL' and moderation_status = 'APPROVED')
                    or (seller_type = 'BUSINESS' and publication_source = 'BUSINESS_SELF_PUBLISHED')
                  )
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
                    publication_source = null,
                    published_at = null,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and status in ('PENDING_REVIEW', 'ACTIVE', 'CLOSED', 'CHANGES_REQUESTED')
                """,
                Timestamp.from(now),
                listingId);
    }

    public int submitForReview(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'PENDING_REVIEW',
                    moderation_status = 'PENDING',
                    publication_source = null,
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
            String publicationSource,
            Instant publishedAt,
            Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = ?,
                    moderation_status = ?,
                    publication_source = ?,
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
                publicationSource,
                publishedAt == null ? null : Timestamp.from(publishedAt),
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    // Updates a paused business item while keeping it private until the seller explicitly relists it.
    public int updatePausedBusinessStoreItem(
            String listingId,
            long expectedVersion,
            ListingDraftUpdate update,
            Instant now) {
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
                  and seller_type = 'BUSINESS'
                  and status = 'PAUSED'
                  and publication_source = 'BUSINESS_SELF_PUBLISHED'
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

    public int publishBusinessStoreItem(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'ACTIVE',
                    moderation_status = 'NOT_SUBMITTED',
                    publication_source = 'BUSINESS_SELF_PUBLISHED',
                    published_at = ?,
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and seller_type = 'BUSINESS'
                  and status = 'DRAFT'
                  and version = ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public int pauseBusinessStoreItem(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'PAUSED',
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and seller_type = 'BUSINESS'
                  and status = 'ACTIVE'
                  and publication_source = 'BUSINESS_SELF_PUBLISHED'
                  and version = ?
                """,
                Timestamp.from(now),
                listingId,
                expectedVersion);
    }

    public int relistBusinessStoreItem(String listingId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                update listings
                set status = 'ACTIVE',
                    publication_source = 'BUSINESS_SELF_PUBLISHED',
                    published_at = coalesce(published_at, ?),
                    version = version + 1,
                    updated_at = ?
                where id = ?
                  and seller_type = 'BUSINESS'
                  and status = 'PAUSED'
                  and publication_source = 'BUSINESS_SELF_PUBLISHED'
                  and version = ?
                """,
                Timestamp.from(now),
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
                rs.getString("store_id"),
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
                rs.getString("publication_source"),
                nullableInstant(rs, "published_at"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                null,
                null,
                null,
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
                null,
                null,
                null,
                false,
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
                rs.getLong("visit_count"),
                rs.getLong("like_count"),
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

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
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

    private Instant nullableInstant(java.sql.ResultSet rs, String columnName) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
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
