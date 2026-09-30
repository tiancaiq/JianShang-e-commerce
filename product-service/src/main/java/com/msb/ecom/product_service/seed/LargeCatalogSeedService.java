package com.msb.ecom.product_service.seed;

import com.msb.ecom.product_service.search.ListingSearchProjectionSyncProperties;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.BatchRequest;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.BatchResponse;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.InventoryCandidate;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.InventoryCandidatePage;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.PageMetadata;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.ListingInput;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.ResetResponse;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.Stats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "demo.large-catalog-seed.enabled", havingValue = "true")
public class LargeCatalogSeedService {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final Set<String> CONDITIONS = Set.of("NEW", "OPEN_BOX", "LIKE_NEW", "GOOD", "FAIR", "FOR_PARTS");
    private static final String BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int MAX_BATCH = 1_000;

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final ListingSearchProjectionSyncProperties projectionProperties;
    private final String expectedToken;
    private final Clock clock;

    @Autowired
    public LargeCatalogSeedService(
            JdbcTemplate jdbcTemplate,
            Environment environment,
            ListingSearchProjectionSyncProperties projectionProperties,
            @Value("${commerce.internal-service-token}") String expectedToken) {
        this(jdbcTemplate, environment, projectionProperties, expectedToken, Clock.systemUTC());
    }

    LargeCatalogSeedService(
            JdbcTemplate jdbcTemplate,
            Environment environment,
            ListingSearchProjectionSyncProperties projectionProperties,
            String expectedToken,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
        this.projectionProperties = projectionProperties;
        this.expectedToken = expectedToken;
        this.clock = clock;
    }

    @Transactional
    // Persists a bounded Product-owned batch after validating catalog and listing invariants.
    public BatchResponse upsertBatch(String suppliedToken, BatchRequest request) {
        requireAllowed(suppliedToken);
        validateRequest(request);
        int created = 0;
        int updated = 0;
        Set<String> batchIdentities = new HashSet<>();
        Set<String> batchListingIds = new HashSet<>();
        for (ListingInput listing : request.listings()) {
            validateListing(request.namespace(), listing, batchIdentities, batchListingIds);
            SeedRecord existing = findSeedRecord(request.namespace(), listing.sourceIdentity());
            if (existing != null && !existing.listingId().equals(listing.listingId())) {
                throw new IllegalArgumentException("Seed source identity maps to another listing.");
            }
            if (existing == null && listingExists(listing.listingId())) {
                throw new IllegalArgumentException("Seed listing identity collides with non-seeded data.");
            }
            if (existing == null) {
                insertListing(listing);
                upsertEngagement(listing);
                jdbcTemplate.update("""
                        insert into large_catalog_seed_listings (
                            seed_namespace, source_identity, listing_id, source_domain, created_at
                        ) values (?, ?, ?, ?, ?)
                        """, request.namespace(), listing.sourceIdentity(), listing.listingId(),
                        listing.sourceDomain(), timestamp(clock.instant()));
                if ("INDIVIDUAL".equals(listing.sellerType())) {
                    ensureModerationHistory(request.namespace(), request.reviewerUserId(), listing);
                }
                queueProjection(listing.listingId(), 0, "UPSERT", listing.createdAt());
                created++;
            } else {
                updateListing(listing);
                upsertEngagement(listing);
                updated++;
            }
        }
        return new BatchResponse(request.namespace(), request.listings().size(), created, updated,
                count("select count(*) from large_catalog_seed_listings where seed_namespace = ?",
                        request.namespace()));
    }

    @Transactional(readOnly = true)
    public Stats stats(String suppliedToken, String namespace) {
        requireAllowed(suppliedToken);
        requireNamespace(namespace);
        long total = activeCount(namespace, null);
        long individual = activeCount(namespace, "INDIVIDUAL");
        long business = activeCount(namespace, "BUSINESS");
        List<Long> individualCounts = ownerCounts(namespace, "INDIVIDUAL");
        List<Long> businessCounts = ownerCounts(namespace, "BUSINESS");
        Map<String, Long> categories = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select c.name, count(*) listing_count
                from large_catalog_seed_listings r
                join listings l on l.id = r.listing_id
                join categories c on c.id = l.category_id
                where r.seed_namespace = ? and l.status = 'ACTIVE'
                group by c.id, c.name order by listing_count desc, c.name
                """, rs -> {
                    categories.put(rs.getString("name"), rs.getLong("listing_count"));
                }, namespace);
        Instant now = clock.instant();
        Map<String, Long> ages = new LinkedHashMap<>();
        ages.put("last7Days", ageCount(namespace, now.minus(7, ChronoUnit.DAYS), now));
        ages.put("days8To30", ageCount(namespace, now.minus(30, ChronoUnit.DAYS), now.minus(7, ChronoUnit.DAYS)));
        ages.put("months1To3", ageCount(namespace, now.minus(90, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS)));
        ages.put("months3To6", ageCount(namespace, now.minus(180, ChronoUnit.DAYS), now.minus(90, ChronoUnit.DAYS)));
        ages.put("months6To12", ageCount(namespace, now.minus(365, ChronoUnit.DAYS), now.minus(180, ChronoUnit.DAYS)));
        ages.put("months12To18", ageCount(namespace, now.minus(548, ChronoUnit.DAYS), now.minus(365, ChronoUnit.DAYS)));
        Map<String, Object> engagementValues = jdbcTemplate.queryForMap("""
                select coalesce(sum(es.visit_count), 0) total_views,
                       coalesce(sum(es.like_count), 0) total_likes,
                       coalesce(max(es.visit_count), 0) max_views,
                       coalesce(max(es.like_count), 0) max_likes,
                       sum(case when es.visit_count=0 then 1 else 0 end) zero_views,
                       sum(case when es.like_count=0 then 1 else 0 end) zero_likes
                from large_catalog_seed_listings r
                join listing_engagement_stats es on es.listing_id=r.listing_id
                where r.seed_namespace=?
                """, namespace);
        Map<String, Long> engagement = new LinkedHashMap<>();
        engagement.put("totalViews", number(engagementValues.get("total_views")));
        engagement.put("totalLikes", number(engagementValues.get("total_likes")));
        engagement.put("maxViews", number(engagementValues.get("max_views")));
        engagement.put("maxLikes", number(engagementValues.get("max_likes")));
        engagement.put("zeroViewListings", number(engagementValues.get("zero_views")));
        engagement.put("zeroLikeListings", number(engagementValues.get("zero_likes")));
        Map<String, Object> bounds = jdbcTemplate.queryForMap("""
                select min(l.published_at) oldest, max(l.published_at) newest
                from large_catalog_seed_listings r join listings l on l.id = r.listing_id
                where r.seed_namespace = ? and l.status = 'ACTIVE'
                """, namespace);
        return new Stats(
                namespace, total, individual, business,
                individualCounts.size(), average(individualCounts), median(individualCounts),
                businessCounts.size(), average(businessCounts),
                categories, ages, engagement, instant(bounds.get("oldest")), instant(bounds.get("newest")),
                count("""
                        select count(*) from large_catalog_seed_listings r join listings l on l.id=r.listing_id
                        where r.seed_namespace=? and (l.created_at > utc_timestamp(6)
                            or l.published_at > utc_timestamp(6) or l.updated_at > utc_timestamp(6))
                        """, namespace),
                count("""
                        select count(*) from large_catalog_seed_listings r join listings l on l.id=r.listing_id
                        where r.seed_namespace=? and (l.created_at > l.published_at
                            or l.published_at > l.updated_at)
                        """, namespace),
                count("""
                        select count(*) from large_catalog_seed_listings r join listings l on l.id=r.listing_id
                        where r.seed_namespace=? and l.price_amount <= 0
                        """, namespace),
                count("""
                        select count(*) from large_catalog_seed_listings r join listings l on l.id=r.listing_id
                        where r.seed_namespace=? and l.quantity < 1
                        """, namespace),
                count("""
                        select count(*) from large_catalog_seed_listings r
                        left join listing_engagement_stats es on es.listing_id=r.listing_id
                        where r.seed_namespace=? and (es.listing_id is null or es.visit_count < 0
                            or es.like_count < 0 or es.like_count > es.visit_count)
                        """, namespace),
                count("""
                        select count(*) from (
                            select source_identity from large_catalog_seed_listings where seed_namespace=?
                            group by source_identity having count(*) > 1
                        ) duplicates
                        """, namespace),
                scalarCount("select count(*) from listing_search_projection_work where state='PENDING'"));
    }

    @Transactional(readOnly = true)
    // Exposes only registry-owned business IDs and catalog quantity suggestions to the opt-in Inventory fixture.
    public InventoryCandidatePage inventoryCandidates(
            String suppliedToken,
            String namespace,
            String cursor,
            Integer limit) {
        requireAllowed(suppliedToken);
        requireNamespace(namespace);
        if (cursor != null) {
            requireId(cursor, "inventory cursor");
        }
        int pageLimit = limit == null ? 500 : limit;
        if (pageLimit < 1 || pageLimit > MAX_BATCH) {
            throw new IllegalArgumentException("Inventory candidate limit must be between 1 and 1000.");
        }
        List<Object> parameters = new ArrayList<>();
        parameters.add(namespace);
        String cursorClause = "";
        if (cursor != null) {
            cursorClause = " and l.id > ?";
            parameters.add(cursor);
        }
        parameters.add(pageLimit + 1);
        List<InventoryCandidate> fetched = jdbcTemplate.query("""
                        select l.id, l.business_id, l.quantity
                        from large_catalog_seed_listings r
                        join listings l on l.id = r.listing_id
                        where r.seed_namespace = ?
                          and l.seller_type = 'BUSINESS'
                          and l.status = 'ACTIVE'
                        %s
                        order by l.id
                        limit ?
                        """.formatted(cursorClause),
                (rs, rowNum) -> new InventoryCandidate(
                        rs.getString("id"),
                        rs.getString("business_id"),
                        rs.getInt("quantity")),
                parameters.toArray());
        boolean hasMore = fetched.size() > pageLimit;
        List<InventoryCandidate> page = hasMore ? fetched.subList(0, pageLimit) : fetched;
        String nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).listingId() : null;
        return new InventoryCandidatePage(List.copyOf(page), new PageMetadata(nextCursor, hasMore));
    }

    @Transactional
    // Removes only registry-owned listings and leaves manual/developer catalog rows untouched.
    public ResetResponse reset(String suppliedToken, String namespace) {
        requireAllowed(suppliedToken);
        requireNamespace(namespace);
        List<ListingVersion> listings = jdbcTemplate.query("""
                select l.id, l.version from listings l
                join large_catalog_seed_listings r on r.listing_id = l.id
                where r.seed_namespace = ? order by l.id
                for update
                """, (rs, rowNum) -> new ListingVersion(rs.getString("id"), rs.getLong("version")), namespace);
        if (listings.isEmpty()) {
            return new ResetResponse(namespace, 0, 0);
        }
        String ids = placeholders(listings.size());
        Object[] args = listings.stream().map(ListingVersion::id).toArray();
        jdbcTemplate.update("delete from moderation_case_events where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_moderation_decisions where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from moderation_cases where subject_listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_visits where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_likes where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_engagement_stats where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_images where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_media_objects where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_attributes where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from listing_search_projection_work where listing_id in (" + ids + ")", args);
        jdbcTemplate.update("delete from large_catalog_seed_listings where seed_namespace = ?", namespace);
        jdbcTemplate.update("delete from listings where id in (" + ids + ")", args);
        long queued = 0;
        if (projectionProperties.enabled()) {
            Instant now = clock.instant();
            for (int index = 0; index < listings.size(); index++) {
                ListingVersion listing = listings.get(index);
                queueProjection(listing.id(), listing.version() + 1, "DELETE", now.plusNanos(index));
                queued++;
            }
        }
        return new ResetResponse(namespace, listings.size(), queued);
    }

    private void insertListing(ListingInput value) {
        jdbcTemplate.update("""
                insert into listings (
                    id, seller_type, individual_seller_user_id, business_id, store_id,
                    category_id, category_rule_version, title, description, condition_code,
                    condition_notes, price_amount, currency, negotiable, sku, quantity,
                    public_city, public_region, payment_preferences_json, delivery_preferences_json,
                    status, moderation_status, publication_source, published_at, version,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, null, null,
                          'ACTIVE', 'APPROVED', ?, ?, 0, ?, ?)
                """, value.listingId(), value.sellerType(), value.individualSellerUserId(),
                value.businessId(), value.storeId(), value.categoryId(), value.title(),
                value.description(), value.condition(), value.conditionNotes(), value.priceAmount(),
                value.currency(), value.negotiable(), value.sku(), value.quantity(),
                value.publicCity(), value.publicRegion(), publicationSource(value),
                timestamp(value.publishedAt()), timestamp(value.createdAt()), timestamp(value.updatedAt()));
    }

    private void updateListing(ListingInput value) {
        jdbcTemplate.update("""
                update listings set seller_type=?, individual_seller_user_id=?, business_id=?, store_id=?,
                    category_id=?, category_rule_version=1, title=?, description=?, condition_code=?,
                    condition_notes=?, price_amount=?, currency=?, negotiable=?, sku=?, quantity=?,
                    public_city=?, public_region=?, status='ACTIVE', moderation_status='APPROVED',
                    publication_source=?, published_at=?, created_at=?, updated_at=?
                where id=?
                """, value.sellerType(), value.individualSellerUserId(), value.businessId(),
                value.storeId(), value.categoryId(), value.title(), value.description(), value.condition(),
                value.conditionNotes(), value.priceAmount(), value.currency(), value.negotiable(),
                value.sku(), value.quantity(), value.publicCity(), value.publicRegion(),
                publicationSource(value), timestamp(value.publishedAt()), timestamp(value.createdAt()),
                timestamp(value.updatedAt()), value.listingId());
    }

    private void upsertEngagement(ListingInput value) {
        jdbcTemplate.update("""
                insert into listing_engagement_stats (listing_id, visit_count, like_count, updated_at)
                values (?, ?, ?, ?)
                on duplicate key update visit_count=values(visit_count),
                    like_count=values(like_count), updated_at=values(updated_at)
                """, value.listingId(), value.visitCount(), value.likeCount(), timestamp(value.updatedAt()));
    }

    private void ensureModerationHistory(String namespace, String reviewer, ListingInput listing) {
        String caseId = stableId(namespace + "|case|" + listing.listingId());
        String decisionId = stableId(namespace + "|decision|" + listing.listingId());
        String createdEventId = stableId(namespace + "|case-created|" + listing.listingId());
        String resolvedEventId = stableId(namespace + "|case-resolved|" + listing.listingId());
        jdbcTemplate.update("""
                insert into moderation_cases (
                    id, case_type, subject_listing_id, subject_seller_type,
                    subject_individual_seller_user_id, subject_business_id, submitted_by_user_id,
                    status, priority, assigned_admin_user_id, version, created_at, updated_at, resolved_at
                ) values (?, 'LISTING_REVIEW', ?, 'INDIVIDUAL', ?, null, ?, 'RESOLVED', 'NORMAL',
                          ?, 1, ?, ?, ?)
                on duplicate key update id=id
                """, caseId, listing.listingId(), listing.individualSellerUserId(),
                listing.individualSellerUserId(), reviewer, timestamp(listing.createdAt()),
                timestamp(listing.approvedAt()), timestamp(listing.approvedAt()));
        jdbcTemplate.update("""
                insert into listing_moderation_decisions (
                    id, listing_id, moderation_case_id, decision, reason, reviewer_user_id,
                    listing_version, previous_state, new_state, correlation_id, created_at
                ) values (?, ?, ?, 'APPROVE', 'Approved development catalog fixture.', ?, 0,
                          'PENDING_REVIEW', 'ACTIVE', ?, ?)
                on duplicate key update id=id
                """, decisionId, listing.listingId(), caseId, reviewer,
                "seed-" + namespace, timestamp(listing.approvedAt()));
        jdbcTemplate.update("""
                insert into moderation_case_events (
                    id, moderation_case_id, listing_id, event_type, actor_user_id,
                    previous_state, new_state, previous_assigned_admin_user_id,
                    new_assigned_admin_user_id, reason, correlation_id, created_at
                ) values (?, ?, ?, 'CASE_CREATED', ?, null, 'OPEN', null, null,
                          'Submitted development catalog fixture.', ?, ?),
                         (?, ?, ?, 'CASE_RESOLVED', ?, 'CLAIMED', 'RESOLVED', ?, ?,
                          'Approved development catalog fixture.', ?, ?)
                on duplicate key update id=id
                """, createdEventId, caseId, listing.listingId(), listing.individualSellerUserId(),
                "seed-" + namespace, timestamp(listing.createdAt()),
                resolvedEventId, caseId, listing.listingId(), reviewer, reviewer, reviewer,
                "seed-" + namespace, timestamp(listing.approvedAt()));
    }

    private void queueProjection(String listingId, long version, String operation, Instant occurredAt) {
        if (!projectionProperties.enabled()) {
            return;
        }
        jdbcTemplate.update("""
                insert into listing_search_projection_work (
                    work_id, listing_id, listing_version, operation, state, attempt_count,
                    next_attempt_at, claim_token, claim_expires_at, last_error_code,
                    completed_at, created_at, replay_sequence
                ) values (?, ?, ?, ?, 'PENDING', 0, ?, null, null, null, null, ?, 0)
                on duplicate key update work_id=work_id
                """, stableId("projection|" + operation + "|" + listingId + "|" + version),
                listingId, version, operation, timestamp(occurredAt), timestamp(occurredAt));
    }

    private void validateRequest(BatchRequest request) {
        if (request == null || request.listings() == null || request.listings().isEmpty()
                || request.listings().size() > MAX_BATCH) {
            throw new IllegalArgumentException("Seed listing batch must contain 1 to 1000 rows.");
        }
        requireNamespace(request.namespace());
        requireId(request.reviewerUserId(), "reviewer user");
    }

    private void validateListing(String namespace, ListingInput value,
            Set<String> sourceIdentities, Set<String> listingIds) {
        if (value == null) {
            throw new IllegalArgumentException("Seed listing is required.");
        }
        requireId(value.listingId(), "listing");
        if (!sourceIdentities.add(value.sourceIdentity()) || !listingIds.add(value.listingId())) {
            throw new IllegalArgumentException("Seed batch contains duplicate identities.");
        }
        if (!text(value.sourceIdentity(), 160) || !text(value.sourceDomain(), 80)
                || !text(value.title(), 180) || !text(value.description(), 20_000)
                || !CONDITIONS.contains(value.condition())
                || value.priceAmount() == null || value.priceAmount().compareTo(BigDecimal.ZERO) <= 0
                || !"USD".equals(value.currency()) || value.quantity() < 1
                || value.visitCount() < 0 || value.likeCount() < 0 || value.likeCount() > value.visitCount()
                || value.createdAt() == null || value.approvedAt() == null
                || value.publishedAt() == null || value.updatedAt() == null
                || value.createdAt().isAfter(value.approvedAt())
                || value.approvedAt().isAfter(value.publishedAt())
                || value.publishedAt().isAfter(value.updatedAt())
                || value.updatedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Seed listing violates content, price, inventory, or timestamp rules.");
        }
        if (!activeCreationCategory(value.categoryId())) {
            throw new IllegalArgumentException("Seed listing category is not active and creation-enabled.");
        }
        if ("INDIVIDUAL".equals(value.sellerType())) {
            requireId(value.individualSellerUserId(), "individual seller");
            if (value.businessId() != null || value.storeId() != null || value.sku() != null
                    || !text(value.publicCity(), 120) || !text(value.publicRegion(), 120)) {
                throw new IllegalArgumentException("Individual seed listing ownership is invalid.");
            }
        } else if ("BUSINESS".equals(value.sellerType())) {
            requireId(value.businessId(), "business");
            requireId(value.storeId(), "store");
            if (value.individualSellerUserId() != null || value.negotiable()
                    || !text(value.sku(), 120)) {
                throw new IllegalArgumentException("Business seed listing ownership is invalid.");
            }
        } else {
            throw new IllegalArgumentException("Seed seller type is invalid.");
        }
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Seed namespace is invalid.");
        }
    }

    private boolean activeCreationCategory(String categoryId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from categories c
                where c.id=? and c.status='ACTIVE' and c.listing_creation_allowed=true
                  and c.listing_submission_allowed=true
                """, Integer.class, categoryId);
        return count != null && count == 1;
    }

    private SeedRecord findSeedRecord(String namespace, String sourceIdentity) {
        List<SeedRecord> values = jdbcTemplate.query("""
                select listing_id from large_catalog_seed_listings
                where seed_namespace=? and source_identity=?
                """, (rs, rowNum) -> new SeedRecord(rs.getString("listing_id")), namespace, sourceIdentity);
        return values.isEmpty() ? null : values.get(0);
    }

    private boolean listingExists(String listingId) {
        Long value = jdbcTemplate.queryForObject("select count(*) from listings where id=?", Long.class, listingId);
        return value != null && value > 0;
    }

    private long activeCount(String namespace, String sellerType) {
        String suffix = sellerType == null ? "" : " and l.seller_type='" + sellerType + "'";
        return count("""
                select count(*) from large_catalog_seed_listings r join listings l on l.id=r.listing_id
                where r.seed_namespace=? and l.status='ACTIVE'
                """ + suffix, namespace);
    }

    private List<Long> ownerCounts(String namespace, String sellerType) {
        String owner = "INDIVIDUAL".equals(sellerType) ? "l.individual_seller_user_id" : "l.business_id";
        return jdbcTemplate.queryForList("""
                select count(*) listing_count from large_catalog_seed_listings r
                join listings l on l.id=r.listing_id
                where r.seed_namespace=? and l.status='ACTIVE' and l.seller_type=?
                group by %s order by listing_count
                """.formatted(owner), Long.class, namespace, sellerType);
    }

    private long ageCount(String namespace, Instant fromInclusive, Instant toExclusive) {
        Long value = jdbcTemplate.queryForObject("""
                select count(*) from large_catalog_seed_listings r join listings l on l.id=r.listing_id
                where r.seed_namespace=? and l.status='ACTIVE' and l.published_at>=? and l.published_at<?
                """, Long.class, namespace, timestamp(fromInclusive), timestamp(toExclusive));
        return value == null ? 0 : value;
    }

    private double average(List<Long> values) {
        return values.isEmpty() ? 0 : values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private String publicationSource(ListingInput value) {
        return "BUSINESS".equals(value.sellerType()) ? "BUSINESS_SELF_PUBLISHED" : "ADMIN_REVIEW";
    }

    private void requireAllowed(String suppliedToken) {
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("Internal service authentication is required.");
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("Large catalog seed is unavailable in production.");
        }
    }

    private void requireNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Seed namespace is invalid.");
        }
    }

    private void requireId(String value, String field) {
        if (value == null || !ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Seed " + field + " identity is invalid.");
        }
    }

    private boolean text(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max;
    }

    private long count(String sql, String namespace) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, namespace);
        return value == null ? 0 : value;
    }

    private long scalarCount(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(ZoneOffset.UTC);
        }
        return null;
    }

    static String stableId(String source) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        StringBuilder value = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte next : digest) {
            buffer = (buffer << 8) | (next & 0xff);
            bits += 8;
            while (bits >= 5 && value.length() < 26) {
                bits -= 5;
                value.append(BASE32.charAt((buffer >> bits) & 31));
            }
            if (value.length() == 26) {
                break;
            }
        }
        return value.toString();
    }

    private record SeedRecord(String listingId) { }
    private record ListingVersion(String id, long version) { }
}
