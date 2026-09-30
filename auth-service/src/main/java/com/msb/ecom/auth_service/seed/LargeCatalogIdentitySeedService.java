package com.msb.ecom.auth_service.seed;

import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.BusinessSeller;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.IndividualSeller;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Request;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.ResetResponse;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Response;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Stats;
import com.msb.ecom.auth_service.service.InternalCommerceAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "demo.large-catalog-seed.enabled", havingValue = "true")
public class LargeCatalogIdentitySeedService {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final String BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int BATCH_SIZE = 500;
    private static final List<Location> LOCATIONS = List.of(
            new Location("Irvine", "CA"), new Location("Costa Mesa", "CA"),
            new Location("Long Beach", "CA"), new Location("Pasadena", "CA"),
            new Location("San Diego", "CA"), new Location("Sacramento", "CA"),
            new Location("San Jose", "CA"), new Location("Oakland", "CA"));
    private static final String[] GIVEN_NAMES = {
            "Alex", "Maya", "Jordan", "Noah", "Sofia", "Eli", "Riley", "Avery",
            "Mina", "Leo", "Nora", "Kai", "Lena", "Theo", "Iris", "Miles"
    };
    private static final String[] FAMILY_NAMES = {
            "Chen", "Rivera", "Patel", "Kim", "Nguyen", "Lewis", "Garcia", "Young",
            "Bennett", "Shah", "Walker", "Park", "Martinez", "Brooks", "Li", "Morgan"
    };
    private static final String[] STORE_PREFIXES = {
            "Northstar", "Cedar", "Harbor", "Juniper", "Bluebird", "Evergreen", "Solstice",
            "Redwood", "Silverline", "Brighton", "Orchard", "Summit", "Willow", "Atlas"
    };
    private static final Map<String, String[]> STORE_SUFFIXES = Map.ofEntries(
            Map.entry("electronics", new String[]{"Tech", "Digital", "Electronics", "Devices"}),
            Map.entry("home", new String[]{"Living", "Home", "Housewares", "Interiors"}),
            Map.entry("clothing", new String[]{"Apparel", "Style", "Wardrobe", "Fashion"}),
            Map.entry("books-media", new String[]{"Books", "Media", "Press", "Entertainment"}),
            Map.entry("automotive", new String[]{"Auto", "Motors", "Garage", "Road"}),
            Map.entry("outdoors", new String[]{"Outdoors", "Trail", "Adventure", "Sport"}),
            Map.entry("pets", new String[]{"Pet", "Paws", "Companion", "Pet Supply"}),
            Map.entry("kids-toys", new String[]{"Toys", "Play", "Kids", "Family"}),
            Map.entry("music", new String[]{"Music", "Sound", "Instruments", "Audio"}),
            Map.entry("office-crafts", new String[]{"Office", "Craft", "Studio", "Creative"}),
            Map.entry("beauty", new String[]{"Beauty", "Care", "Wellness", "Essentials"}));
    private static final String[] SEGMENTS = {
            "electronics", "home", "clothing", "books-media", "automotive", "outdoors",
            "pets", "kids-toys", "music", "office-crafts", "beauty"
    };

    private final JdbcTemplate jdbcTemplate;
    private final InternalCommerceAuthenticator authenticator;
    private final Environment environment;
    private final Clock clock;

    @Autowired
    public LargeCatalogIdentitySeedService(
            JdbcTemplate jdbcTemplate,
            InternalCommerceAuthenticator authenticator,
            Environment environment) {
        this(jdbcTemplate, authenticator, environment, Clock.systemUTC());
    }

    LargeCatalogIdentitySeedService(
            JdbcTemplate jdbcTemplate,
            InternalCommerceAuthenticator authenticator,
            Environment environment,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.authenticator = authenticator;
        this.environment = environment;
        this.clock = clock;
    }

    @Transactional
    // Creates only Auth-owned identities and approval state for the explicitly enabled local catalog fixture.
    public Response ensure(String suppliedToken, Request request) {
        requireAllowed(suppliedToken);
        validate(request);
        Instant now = clock.instant();
        SplittableRandom random = new SplittableRandom(request.randomSeed());
        String reviewerId = stableId(request.namespace(), "reviewer", 0);
        Timestamp reviewerCreated = timestamp(now.minus(request.maxListingAgeDays() + 420L, ChronoUnit.DAYS));
        upsertUser(reviewerId, "seed-" + request.namespace() + "-reviewer", null,
                "Catalog Seed Reviewer", "seed-" + request.namespace() + "-reviewer", reviewerCreated);
        jdbcTemplate.update("""
                insert into user_roles (user_id, role_id, granted_by, granted_at)
                values (?, 'PLATFORM_ADMIN', null, ?)
                on duplicate key update granted_at = granted_at
                """, reviewerId, reviewerCreated);
        register(request.namespace(), "USER", reviewerId, now);

        List<IndividualSeller> individuals = new ArrayList<>(request.individualSellerCount());
        for (int index = 0; index < request.individualSellerCount(); index++) {
            Location location = LOCATIONS.get(index % LOCATIONS.size());
            Instant createdAt = now.minus(
                    request.maxListingAgeDays() + 45L + random.nextLong(366), ChronoUnit.DAYS)
                    .minusSeconds(random.nextLong(86_400));
            String userId = stableId(request.namespace(), "individual-user", index);
            String profileId = stableId(request.namespace(), "individual-profile", index);
            String displayName = GIVEN_NAMES[index % GIVEN_NAMES.length] + " "
                    + FAMILY_NAMES[(index / GIVEN_NAMES.length) % FAMILY_NAMES.length];
            String ordinal = String.format(Locale.ROOT, "%04d", index + 1);
            upsertUser(userId, "seed-" + request.namespace() + "-individual-" + ordinal,
                    "individual." + ordinal + "@" + request.namespace() + ".seed.invalid",
                    displayName, "seed-" + request.namespace() + "-ind-" + ordinal,
                    timestamp(createdAt));
            jdbcTemplate.update("""
                    insert into user_roles (user_id, role_id, granted_by, granted_at)
                    values (?, 'BUYER', null, ?), (?, 'INDIVIDUAL_SELLER', null, ?)
                    on duplicate key update granted_at = granted_at
                    """, userId, timestamp(createdAt), userId, timestamp(createdAt));
            jdbcTemplate.update("""
                    insert into individual_seller_profiles (
                        id, user_id, public_city, public_region, terms_version, status,
                        completed_sales_count, version, created_at, updated_at
                    ) values (?, ?, ?, ?, '2026-09', 'ACTIVE', 0, 0, ?, ?)
                    on duplicate key update public_city = values(public_city),
                        public_region = values(public_region), status = 'ACTIVE'
                    """, profileId, userId, location.city(), location.region(),
                    timestamp(createdAt), timestamp(createdAt));
            register(request.namespace(), "USER", userId, now);
            register(request.namespace(), "INDIVIDUAL_PROFILE", profileId, now);
            individuals.add(new IndividualSeller(userId, profileId, displayName,
                    location.city(), location.region(), createdAt));
        }

        List<BusinessSeller> businesses = new ArrayList<>(request.businessSellerCount());
        for (int index = 0; index < request.businessSellerCount(); index++) {
            Location location = LOCATIONS.get((index * 3) % LOCATIONS.size());
            String segment = SEGMENTS[index % SEGMENTS.length];
            String prefix = STORE_PREFIXES[index % STORE_PREFIXES.length];
            String[] suffixes = STORE_SUFFIXES.get(segment);
            String storeName = prefix + " " + suffixes[(index / STORE_PREFIXES.length) % suffixes.length]
                    + " " + String.format(Locale.ROOT, "%03d", index + 1);
            String slug = slug(storeName);
            String ordinal = String.format(Locale.ROOT, "%04d", index + 1);
            String ownerId = stableId(request.namespace(), "business-owner", index);
            String applicationId = stableId(request.namespace(), "business-application", index);
            String businessId = stableId(request.namespace(), "business", index);
            String storeId = stableId(request.namespace(), "store", index);
            Instant createdAt = now.minus(
                    request.maxListingAgeDays() + 60L + random.nextLong(366), ChronoUnit.DAYS)
                    .minusSeconds(random.nextLong(86_400));
            Instant submittedAt = createdAt.plus(1 + random.nextLong(10), ChronoUnit.DAYS);
            Instant approvedAt = submittedAt.plus(1 + random.nextLong(8), ChronoUnit.DAYS);
            Timestamp created = timestamp(createdAt);
            Timestamp submitted = timestamp(submittedAt);
            Timestamp approved = timestamp(approvedAt);
            String email = "business." + ordinal + "@" + request.namespace() + ".seed.invalid";
            upsertUser(ownerId, "seed-" + request.namespace() + "-business-" + ordinal,
                    email, storeName + " Owner", "seed-" + request.namespace() + "-biz-" + ordinal, created);
            jdbcTemplate.update("""
                    insert into user_roles (user_id, role_id, granted_by, granted_at)
                    values (?, 'BUYER', null, ?)
                    on duplicate key update granted_at = granted_at
                    """, ownerId, created);
            jdbcTemplate.update("""
                    insert into business_applications (
                        id, applicant_user_id, legal_name, business_type, country,
                        contact_email, contact_phone, public_city, public_region,
                        website_url, description, status, submitted_at, reviewer_user_id,
                        approved_business_id, decision_reason, decided_at, version,
                        created_at, updated_at
                    ) values (?, ?, ?, 'LLC', 'US', ?, null, ?, ?, null, ?, 'APPROVED',
                              ?, ?, null, 'Approved development catalog fixture.', ?, 1, ?, ?)
                    on duplicate key update legal_name = values(legal_name), status = 'APPROVED',
                        reviewer_user_id = values(reviewer_user_id), decided_at = values(decided_at),
                        updated_at = values(updated_at)
                    """, applicationId, ownerId, storeName + " LLC", email,
                    location.city(), location.region(),
                    "Development catalog store specializing in " + segment + ".",
                    submitted, reviewerId, approved, created, approved);
            jdbcTemplate.update("""
                    insert into businesses (
                        id, application_id, legal_name, business_type, country, status,
                        approved_at, approved_by, version, created_at, updated_at
                    ) values (?, ?, ?, 'LLC', 'US', 'ACTIVE', ?, ?, 0, ?, ?)
                    on duplicate key update legal_name = values(legal_name), status = 'ACTIVE',
                        approved_at = values(approved_at), updated_at = values(updated_at)
                    """, businessId, applicationId, storeName + " LLC", approved,
                    reviewerId, created, approved);
            jdbcTemplate.update("update business_applications set approved_business_id = ? where id = ?",
                    businessId, applicationId);
            jdbcTemplate.update("""
                    insert into stores (
                        id, business_id, slug, name, description, logo_url, banner_url,
                        support_email, support_phone, status, version, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, null, null, ?, null, 'ACTIVE', 0, ?, ?)
                    on duplicate key update name = values(name), description = values(description),
                        support_email = values(support_email), status = 'ACTIVE', updated_at = values(updated_at)
                    """, storeId, businessId, slug, storeName,
                    "Curated " + segment + " products for the JianShang development marketplace.",
                    email, approved, approved);
            jdbcTemplate.update("""
                    insert into business_memberships (
                        business_id, user_id, role, status, invited_by, created_at, updated_at
                    ) values (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?)
                    on duplicate key update role = 'OWNER', status = 'ACTIVE', updated_at = values(updated_at)
                    """, businessId, ownerId, reviewerId, approved, approved);
            String eventId = stableId(request.namespace(), "business-approval-event", index);
            jdbcTemplate.update("""
                    insert into business_verification_events (
                        id, provider_event_id, application_id, source, event_type, outcome,
                        reason, payload_hash, actor_user_id, created_at
                    ) values (?, null, ?, 'ADMIN', 'BUSINESS_APPLICATION_DECISION', 'APPROVE',
                              'Approved development catalog fixture.', null, ?, ?)
                    on duplicate key update id = id
                    """, eventId, applicationId, reviewerId, approved);
            register(request.namespace(), "USER", ownerId, now);
            register(request.namespace(), "BUSINESS_APPLICATION", applicationId, now);
            register(request.namespace(), "BUSINESS", businessId, now);
            register(request.namespace(), "STORE", storeId, now);
            businesses.add(new BusinessSeller(ownerId, applicationId, businessId, storeId,
                    storeName, slug, segment, location.city(), location.region(), createdAt, approvedAt));
        }
        return new Response(request.namespace(), reviewerId, List.copyOf(individuals),
                List.copyOf(businesses), true);
    }

    @Transactional(readOnly = true)
    public Stats stats(String suppliedToken, String namespace) {
        requireAllowed(suppliedToken);
        requireNamespace(namespace);
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select entity_type, count(*) entity_count
                from large_catalog_seed_entities where seed_namespace = ?
                group by entity_type order by entity_type
                """, rs -> {
                    counts.put(rs.getString("entity_type"), rs.getLong("entity_count"));
                }, namespace);
        long individuals = count("""
                select count(*) from individual_seller_profiles p
                join large_catalog_seed_entities e on e.entity_id = p.id and e.entity_type = 'INDIVIDUAL_PROFILE'
                where e.seed_namespace = ? and p.status = 'ACTIVE'
                """, namespace);
        long businesses = count("""
                select count(*) from businesses b
                join large_catalog_seed_entities e on e.entity_id = b.id and e.entity_type = 'BUSINESS'
                where e.seed_namespace = ? and b.status = 'ACTIVE'
                """, namespace);
        long stores = count("""
                select count(*) from stores s
                join large_catalog_seed_entities e on e.entity_id = s.id and e.entity_type = 'STORE'
                where e.seed_namespace = ? and s.status = 'ACTIVE'
                """, namespace);
        return new Stats(namespace, counts, individuals, businesses, stores);
    }

    @Transactional
    // Deletes only identities registered to this seed namespace; Product listings must be reset first.
    public ResetResponse reset(String suppliedToken, String namespace) {
        requireAllowed(suppliedToken);
        requireNamespace(namespace);
        long total = count("select count(*) from large_catalog_seed_entities where seed_namespace = ?", namespace);
        jdbcTemplate.update("""
                delete e from business_verification_events e
                join large_catalog_seed_entities r on r.entity_id = e.application_id
                where r.seed_namespace = ? and r.entity_type = 'BUSINESS_APPLICATION'
                """, namespace);
        jdbcTemplate.update("""
                delete m from business_memberships m
                join large_catalog_seed_entities r on r.entity_id = m.business_id
                where r.seed_namespace = ? and r.entity_type = 'BUSINESS'
                """, namespace);
        jdbcTemplate.update("""
                delete s from stores s
                join large_catalog_seed_entities r on r.entity_id = s.id
                where r.seed_namespace = ? and r.entity_type = 'STORE'
                """, namespace);
        jdbcTemplate.update("""
                update business_applications a
                join large_catalog_seed_entities r on r.entity_id = a.id
                set a.approved_business_id = null
                where r.seed_namespace = ? and r.entity_type = 'BUSINESS_APPLICATION'
                """, namespace);
        jdbcTemplate.update("""
                delete b from businesses b
                join large_catalog_seed_entities r on r.entity_id = b.id
                where r.seed_namespace = ? and r.entity_type = 'BUSINESS'
                """, namespace);
        jdbcTemplate.update("""
                delete a from business_applications a
                join large_catalog_seed_entities r on r.entity_id = a.id
                where r.seed_namespace = ? and r.entity_type = 'BUSINESS_APPLICATION'
                """, namespace);
        jdbcTemplate.update("""
                delete p from individual_seller_profiles p
                join large_catalog_seed_entities r on r.entity_id = p.id
                where r.seed_namespace = ? and r.entity_type = 'INDIVIDUAL_PROFILE'
                """, namespace);
        jdbcTemplate.update("""
                delete ur from user_roles ur
                join large_catalog_seed_entities r on r.entity_id = ur.user_id
                where r.seed_namespace = ? and r.entity_type = 'USER'
                """, namespace);
        jdbcTemplate.update("""
                delete u from users u
                join large_catalog_seed_entities r on r.entity_id = u.id
                where r.seed_namespace = ? and r.entity_type = 'USER'
                """, namespace);
        jdbcTemplate.update("delete from large_catalog_seed_entities where seed_namespace = ?", namespace);
        return new ResetResponse(namespace, total);
    }

    private void upsertUser(String id, String subject, String email, String displayName,
            String handle, Timestamp createdAt) {
        jdbcTemplate.update("""
                insert into users (
                    id, keycloak_sub, email, email_verified, display_name, public_handle,
                    phone, phone_verified, avatar_url, status, version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, null, false, null, 'ACTIVE', 0, ?, ?)
                on duplicate key update display_name = values(display_name), public_handle = values(public_handle),
                    status = 'ACTIVE', updated_at = updated_at
                """, id, subject, email, email != null, displayName, handle, createdAt, createdAt);
    }

    private void register(String namespace, String type, String id, Instant now) {
        jdbcTemplate.update("""
                insert into large_catalog_seed_entities (
                    seed_namespace, entity_type, entity_id, created_at
                ) values (?, ?, ?, ?)
                on duplicate key update entity_id = entity_id
                """, namespace, type, id, timestamp(now));
    }

    private void requireAllowed(String suppliedToken) {
        authenticator.require(suppliedToken);
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("Large catalog seed is unavailable in production.");
        }
    }

    private void validate(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("Seed request is required.");
        }
        requireNamespace(request.namespace());
        if (request.individualSellerCount() < 1 || request.individualSellerCount() > 5_000
                || request.businessSellerCount() < 1 || request.businessSellerCount() > 1_000
                || request.maxListingAgeDays() < 30 || request.maxListingAgeDays() > 3_650) {
            throw new IllegalArgumentException("Seed identity configuration is outside supported bounds.");
        }
    }

    private void requireNamespace(String namespace) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Seed namespace is invalid.");
        }
    }

    private long count(String sql, String namespace) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, namespace);
        return value == null ? 0 : value;
    }

    static String stableId(String namespace, String type, int index) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((namespace + "|" + type + "|" + index).getBytes(StandardCharsets.UTF_8));
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

    private String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record Location(String city, String region) { }
}
