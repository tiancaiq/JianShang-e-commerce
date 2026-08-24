package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.LocalCartDemoFixtureResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "demo.cart-second-business-fixture.enabled", havingValue = "true")
public class LocalCartDemoFixtureService {

    public static final String APPLICATION_ID = "01KZCARTB00000000000000001";
    public static final String BUSINESS_ID = "01KZCARTB00000000000000002";
    public static final String STORE_ID = "01KZCARTB00000000000000003";
    public static final String OWNER_USER_ID = "01D00000000000000000000002";
    public static final String OWNER_KEYCLOAK_SUB = "33333333-3333-4333-8333-333333333333";
    private static final String SHEN_OWNER_USER_ID = "01KXQ91NH440XN9GWEM4YEZ728";
    private static final String SHEN_APPLICATION_ID = "01KXQAPPL00000000000000001";
    private static final String SHEN_BUSINESS_ID = "01KXQBUSI00000000000000001";
    private static final String SHEN_STORE_ID = SHEN_BUSINESS_ID;
    private static final String REVIEW_EVENT_ID = "01KZCARTB00000000000000004";
    private static final String REVIEWER_USER_ID = "01KZCARTB00000000000000009";
    private static final String STORE_NAME = "Harbor Cart Supply";
    private static final String STORE_SLUG = "harbor-cart-supply";
    private static final String PUBLIC_CITY = "Costa Mesa";
    private static final String PUBLIC_REGION = "CA";

    private final JdbcTemplate jdbcTemplate;
    private final InternalCommerceAuthenticator authenticator;

    public LocalCartDemoFixtureService(
            JdbcTemplate jdbcTemplate,
            InternalCommerceAuthenticator authenticator) {
        this.jdbcTemplate = jdbcTemplate;
        this.authenticator = authenticator;
    }

    @Transactional
    // Restores one Auth-owned local cart fixture without granting a usable admin identity.
    public LocalCartDemoFixtureResponse ensure(String suppliedToken) {
        return ensure(suppliedToken, null, null);
    }

    @Transactional
    // Optionally links the local-only owner to the stable Keycloak username's persisted subject.
    public LocalCartDemoFixtureResponse ensure(String suppliedToken, String ownerSubject) {
        return ensure(suppliedToken, ownerSubject, null);
    }

    @Transactional
    // Links local fixture owners to persisted Keycloak subjects without changing ownership rows.
    public LocalCartDemoFixtureResponse ensure(
            String suppliedToken,
            String ownerSubject,
            String shenOwnerSubject) {
        authenticator.require(suppliedToken);
        String resolvedOwnerSubject = ownerSubject == null || ownerSubject.isBlank()
                ? OWNER_KEYCLOAK_SUB : requireKeycloakSubject(ownerSubject);
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update("""
                insert into users (
                    id, keycloak_sub, email, email_verified, display_name, public_handle,
                    phone, phone_verified, avatar_url, status, version, created_at, updated_at
                ) values (?, ?, ?, true, ?, ?, null, false, null, 'ACTIVE', 0, ?, ?)
                on duplicate key update
                    keycloak_sub = keycloak_sub,
                    email = values(email),
                    email_verified = true,
                    display_name = values(display_name),
                    public_handle = values(public_handle),
                    status = 'ACTIVE',
                    updated_at = values(updated_at)
                """,
                OWNER_USER_ID,
                OWNER_KEYCLOAK_SUB,
                "harbor.seller@msb.local",
                "Harbor Seller",
                "harbor-seller",
                now,
                now);
        if (ownerSubject != null && !ownerSubject.isBlank()) {
            jdbcTemplate.update("update users set keycloak_sub = ?, updated_at = ? where id = ?",
                    resolvedOwnerSubject, now, OWNER_USER_ID);
        }
        if (shenOwnerSubject != null && !shenOwnerSubject.isBlank()) {
            jdbcTemplate.update("""
                    insert into users (
                        id, keycloak_sub, email, email_verified, display_name, public_handle,
                        phone, phone_verified, avatar_url, status, version, created_at, updated_at
                    ) values (?, ?, ?, true, ?, ?, null, false, null, 'ACTIVE', 0, ?, ?)
                    on duplicate key update
                        keycloak_sub = values(keycloak_sub),
                        email = values(email),
                        email_verified = true,
                        display_name = values(display_name),
                        public_handle = values(public_handle),
                        status = 'ACTIVE',
                        updated_at = values(updated_at)
                    """,
                    SHEN_OWNER_USER_ID,
                    requireKeycloakSubject(shenOwnerSubject),
                    "shen.ban2@mycnmipss.org",
                    "Shen Ban",
                    "shen-ban",
                    now,
                    now);
        }

        jdbcTemplate.update("""
                insert into users (
                    id, keycloak_sub, email, email_verified, display_name, public_handle,
                    phone, phone_verified, avatar_url, status, version, created_at, updated_at
                ) values (?, ?, null, false, ?, ?, null, false, null, 'ACTIVE', 0, ?, ?)
                on duplicate key update
                    display_name = values(display_name),
                    public_handle = values(public_handle),
                    status = 'ACTIVE',
                    updated_at = values(updated_at)
                """,
                REVIEWER_USER_ID,
                "local-cart-fixture-reviewer",
                "Local cart fixture reviewer",
                "local-cart-fixture-reviewer",
                now,
                now);
        jdbcTemplate.update("""
                insert into user_roles (user_id, role_id, granted_by, granted_at)
                values (?, 'PLATFORM_ADMIN', null, ?)
                on duplicate key update granted_at = granted_at
                """, REVIEWER_USER_ID, now);
        jdbcTemplate.update("""
                insert into admin_role_assignments (
                    id, admin_user_id, role_id, status, effective_at, expires_at,
                    granted_by_admin_id, reason, correlation_id, version, created_at, updated_at
                ) values ('01CARTF1XTUREP1ATF0RMADM01', ?, 'SUPER_ADMIN', 'ACTIVE', ?, null,
                          null, 'Local cart demo fixture', 'local-cart-demo-fixture', 0, ?, ?)
                on duplicate key update status = 'ACTIVE', expires_at = null,
                    revoked_at = null, revoked_by_admin_id = null, revocation_reason = null,
                    updated_at = values(updated_at)
                """, REVIEWER_USER_ID, now, now, now);
        if (shenOwnerSubject != null && !shenOwnerSubject.isBlank()) {
            ensureShenBusiness(now);
        }
        jdbcTemplate.update("""
                insert into business_applications (
                    id, applicant_user_id, legal_name, business_type, country,
                    contact_email, contact_phone, public_city, public_region,
                    website_url, description, status, submitted_at, reviewer_user_id,
                    approved_business_id, decision_reason, decided_at, version,
                    created_at, updated_at
                ) values (?, ?, ?, 'LLC', 'US', ?, null, ?, ?, null, ?, 'APPROVED',
                          ?, ?, null, ?, ?, 1, ?, ?)
                on duplicate key update
                    applicant_user_id = values(applicant_user_id),
                    legal_name = values(legal_name),
                    contact_email = values(contact_email),
                    public_city = values(public_city),
                    public_region = values(public_region),
                    description = values(description),
                    status = 'APPROVED',
                    reviewer_user_id = values(reviewer_user_id),
                    decision_reason = values(decision_reason),
                    decided_at = values(decided_at),
                    updated_at = values(updated_at)
                """,
                APPLICATION_ID,
                OWNER_USER_ID,
                "Harbor Cart Supply LLC",
                "harbor.seller@msb.local",
                PUBLIC_CITY,
                PUBLIC_REGION,
                "Deterministic second-business fixture for local cart acceptance.",
                now,
                REVIEWER_USER_ID,
                "Approved local cart acceptance fixture.",
                now,
                now,
                now);
        jdbcTemplate.update("""
                insert into businesses (
                    id, application_id, legal_name, business_type, country, status,
                    approved_at, approved_by, version, created_at, updated_at
                ) values (?, ?, ?, 'LLC', 'US', 'ACTIVE', ?, ?, 0, ?, ?)
                on duplicate key update
                    legal_name = values(legal_name),
                    status = 'ACTIVE',
                    updated_at = values(updated_at)
                """,
                BUSINESS_ID,
                APPLICATION_ID,
                "Harbor Cart Supply LLC",
                now,
                REVIEWER_USER_ID,
                now,
                now);
        jdbcTemplate.update("""
                update business_applications
                set approved_business_id = ?, updated_at = ?
                where id = ?
                """, BUSINESS_ID, now, APPLICATION_ID);
        jdbcTemplate.update("""
                insert into stores (
                    id, business_id, slug, name, description, logo_url, banner_url,
                    support_email, support_phone, status, version, created_at, updated_at
                ) values (?, ?, ?, ?, ?, null, null, ?, null, 'ACTIVE', 0, ?, ?)
                on duplicate key update
                    slug = values(slug),
                    name = values(name),
                    description = values(description),
                    support_email = values(support_email),
                    status = 'ACTIVE',
                    updated_at = values(updated_at)
                """,
                STORE_ID,
                BUSINESS_ID,
                STORE_SLUG,
                STORE_NAME,
                "Local-demo storefront for multi-business cart verification.",
                "harbor.seller@msb.local",
                now,
                now);
        jdbcTemplate.update("""
                insert into business_memberships (
                    business_id, user_id, role, status, invited_by, created_at, updated_at
                ) values (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?)
                on duplicate key update
                    role = 'OWNER',
                    status = 'ACTIVE',
                    invited_by = values(invited_by),
                    updated_at = values(updated_at)
                """, BUSINESS_ID, OWNER_USER_ID, REVIEWER_USER_ID, now, now);
        jdbcTemplate.update("""
                delete from business_memberships
                where business_id = ? and user_id <> ?
                """, BUSINESS_ID, OWNER_USER_ID);
        jdbcTemplate.update("""
                insert into business_verification_events (
                    id, provider_event_id, application_id, source, event_type,
                    outcome, reason, payload_hash, actor_user_id, created_at
                ) values (?, null, ?, 'ADMIN', 'BUSINESS_APPLICATION_DECISION',
                          'APPROVE', ?, null, ?, ?)
                on duplicate key update id = id
                """,
                REVIEW_EVENT_ID,
                APPLICATION_ID,
                "Approved local cart acceptance fixture.",
                REVIEWER_USER_ID,
                now);

        return new LocalCartDemoFixtureResponse(
                APPLICATION_ID,
                BUSINESS_ID,
                STORE_ID,
                OWNER_USER_ID,
                STORE_NAME,
                STORE_SLUG,
                true,
                PUBLIC_CITY,
                PUBLIC_REGION);
    }

    // Restores the first seller tenant that the multi-business Commerce fixture depends on.
    private void ensureShenBusiness(Timestamp now) {
        Integer existing = jdbcTemplate.queryForObject(
                "select count(*) from businesses where id = ?", Integer.class, SHEN_BUSINESS_ID);
        if (existing != null && existing == 0) {
            jdbcTemplate.update("""
                    insert into business_applications (
                        id, applicant_user_id, legal_name, business_type, country,
                        contact_email, contact_phone, public_city, public_region,
                        website_url, description, status, submitted_at, reviewer_user_id,
                        approved_business_id, decision_reason, decided_at, version,
                        created_at, updated_at
                    ) values (?, ?, ?, 'SOLE_PROPRIETORSHIP', 'US', ?, null, ?, ?, null, ?,
                              'APPROVED', ?, ?, null, ?, ?, 1, ?, ?)
                    """,
                    SHEN_APPLICATION_ID, SHEN_OWNER_USER_ID, "Shen Ban Demo Store",
                    "shen.ban2@mycnmipss.org", "Irvine", "CA",
                    "Deterministic first-business fixture for local Commerce acceptance.",
                    now, REVIEWER_USER_ID, "Approved local Commerce acceptance fixture.", now, now, now);
            jdbcTemplate.update("""
                    insert into businesses (
                        id, application_id, legal_name, business_type, country, status,
                        approved_at, approved_by, version, created_at, updated_at
                    ) values (?, ?, ?, 'SOLE_PROPRIETORSHIP', 'US', 'ACTIVE', ?, ?, 0, ?, ?)
                    """,
                    SHEN_BUSINESS_ID, SHEN_APPLICATION_ID, "Shen Ban Demo Store",
                    now, REVIEWER_USER_ID, now, now);
            jdbcTemplate.update(
                    "update business_applications set approved_business_id = ?, updated_at = ? where id = ?",
                    SHEN_BUSINESS_ID, now, SHEN_APPLICATION_ID);
        }
        jdbcTemplate.update(
                "update businesses set status = 'ACTIVE', updated_at = ? where id = ?",
                now, SHEN_BUSINESS_ID);
        jdbcTemplate.update("""
                insert into stores (
                    id, business_id, slug, name, description, logo_url, banner_url,
                    support_email, support_phone, status, version, created_at, updated_at
                ) values (?, ?, 'shen-ban-demo-store', 'Shen Ban Demo Store',
                          'Local-demo storefront for multi-business Commerce verification.',
                          null, null, 'shen.ban2@mycnmipss.org', null, 'ACTIVE', 0, ?, ?)
                on duplicate key update
                    slug = values(slug), name = values(name), description = values(description),
                    support_email = values(support_email), status = 'ACTIVE', updated_at = values(updated_at)
                """, SHEN_STORE_ID, SHEN_BUSINESS_ID, now, now);
        jdbcTemplate.update("""
                insert into business_memberships (
                    business_id, user_id, role, status, invited_by, created_at, updated_at
                ) values (?, ?, 'OWNER', 'ACTIVE', ?, ?, ?)
                on duplicate key update role = 'OWNER', status = 'ACTIVE', updated_at = values(updated_at)
                """, SHEN_BUSINESS_ID, SHEN_OWNER_USER_ID, REVIEWER_USER_ID, now, now);
    }

    private String requireKeycloakSubject(String value) {
        String subject = value.trim();
        if (!subject.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Local demo owner subject is invalid.");
        }
        return subject;
    }
}
