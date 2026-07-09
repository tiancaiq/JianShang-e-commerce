package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.BusinessStoreContextResponse;
import com.msb.ecom.auth_service.dto.BusinessStoreResponse;
import com.msb.ecom.auth_service.dto.BusinessStoreUpdateRequest;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.common.core.validation.TextInputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessStoreService {

    private static final String OWNER = "OWNER";
    private static final String MANAGER = "MANAGER";

    private static final RowMapper<BusinessStoreResponse> STORE_ROW_MAPPER = (rs, rowNum) -> new BusinessStoreResponse(
            rs.getString("id"),
            rs.getString("business_id"),
            rs.getString("slug"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("logo_url"),
            rs.getString("banner_url"),
            rs.getString("support_email"),
            rs.getString("support_phone"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    // Returns the approved business store for an active member without exposing other tenants.
    public BusinessStoreResponse getBusinessStore(String businessId) {
        User user = authService.ensureUserEntity();
        requireActiveMembership(businessId, user.getId());
        return findByBusinessId(businessId);
    }

    @Transactional(readOnly = true)
    // Provides the seller portal's current approved business/store context before item workflows load.
    public BusinessStoreContextResponse getCurrentStoreContext() {
        User user = authService.ensureUserEntity();
        return jdbcTemplate.query("""
                        select b.id as business_id,
                               b.legal_name as business_legal_name,
                               b.status as business_status,
                               m.role as membership_role,
                               s.id,
                               s.slug,
                               s.name,
                               s.description,
                               s.logo_url,
                               s.banner_url,
                               s.support_email,
                               s.support_phone,
                               s.status,
                               s.version,
                               s.created_at,
                               s.updated_at
                        from business_memberships m
                        join businesses b on b.id = m.business_id
                        join stores s on s.business_id = b.id
                        where m.user_id = ?
                          and m.status = 'ACTIVE'
                          and b.status = 'ACTIVE'
                          and s.status = 'ACTIVE'
                        order by case m.role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end,
                                 b.created_at desc
                        limit 1
                        """,
                (rs, rowNum) -> {
                    BusinessStoreResponse store = new BusinessStoreResponse(
                            rs.getString("id"),
                            rs.getString("business_id"),
                            rs.getString("slug"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("logo_url"),
                            rs.getString("banner_url"),
                            rs.getString("support_email"),
                            rs.getString("support_phone"),
                            rs.getString("status"),
                            rs.getLong("version"),
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant());
                    String role = rs.getString("membership_role");
                    return new BusinessStoreContextResponse(
                            rs.getString("business_id"),
                            rs.getString("business_legal_name"),
                            rs.getString("business_status"),
                            role,
                            permissionsFor(role),
                            store);
                },
                user.getId())
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Transactional
    // Updates mutable customer-facing store profile fields for owner/manager roles only.
    public BusinessStoreResponse updateBusinessStore(
            String businessId,
            Long expectedVersion,
            BusinessStoreUpdateRequest request) {
        User user = authService.ensureUserEntity();
        requireStoreManager(businessId, user.getId());

        BusinessStoreResponse current = findByBusinessId(businessId);
        if (current.version() != expectedVersion) {
            throw new BusinessStoreVersionConflictException();
        }

        Instant now = Instant.now();
        try {
            int updated = jdbcTemplate.update("""
                    update stores
                    set slug = ?,
                        name = ?,
                        description = ?,
                        logo_url = ?,
                        banner_url = ?,
                        support_email = ?,
                        support_phone = ?,
                        version = version + 1,
                        updated_at = ?
                    where business_id = ?
                      and version = ?
                    """,
                    normalizedSlug(request.slug()),
                    requiredText("Store name", request.name(), 160),
                    optionalText("Description", request.description(), 1000),
                    normalizedUrl("Logo URL", request.logoUrl()),
                    normalizedUrl("Banner URL", request.bannerUrl()),
                    normalizedEmail(request.supportEmail()),
                    normalizedPhone(request.supportPhone()),
                    Timestamp.from(now),
                    businessId,
                    expectedVersion);
            if (updated == 0) {
                throw new BusinessStoreVersionConflictException();
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessStoreSlugConflictException();
        }

        BusinessStoreResponse saved = findByBusinessId(businessId);
        log.info("Updated business store businessId={} storeId={} userId={}",
                businessId, saved.id(), user.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public BusinessStoreResponse getPublicStore(String slug) {
        String normalized = normalizedSlug(slug);
        return jdbcTemplate.query("""
                        select s.*
                        from stores s
                        join businesses b on b.id = s.business_id
                        where s.slug = ?
                          and s.status = 'ACTIVE'
                          and b.status = 'ACTIVE'
                        """, STORE_ROW_MAPPER, normalized)
                .stream()
                .findFirst()
                .orElseThrow(BusinessStoreNotFoundException::new);
    }

    private BusinessStoreResponse findByBusinessId(String businessId) {
        return jdbcTemplate.query("""
                        select *
                        from stores
                        where business_id = ?
                        """, STORE_ROW_MAPPER, businessId)
                .stream()
                .findFirst()
                .orElseThrow(BusinessStoreNotFoundException::new);
    }

    private void requireActiveMembership(String businessId, String userId) {
        Integer businessCount = jdbcTemplate.queryForObject(
                "select count(*) from businesses where id = ?",
                Integer.class,
                businessId);
        if (businessCount == null || businessCount == 0) {
            throw new BusinessStoreNotFoundException();
        }

        Integer membershipCount = jdbcTemplate.queryForObject("""
                select count(*)
                from business_memberships
                where business_id = ?
                  and user_id = ?
                  and status = 'ACTIVE'
                """, Integer.class, businessId, userId);
        if (membershipCount == null || membershipCount == 0) {
            log.warn("Denied business store read businessId={} userId={} reason=missing_membership",
                    businessId, userId);
            throw new BusinessStoreForbiddenException();
        }
    }

    private void requireStoreManager(String businessId, String userId) {
        requireActiveMembership(businessId, userId);
        Integer roleCount = jdbcTemplate.queryForObject("""
                select count(*)
                from business_memberships
                where business_id = ?
                  and user_id = ?
                  and status = 'ACTIVE'
                  and role in (?, ?)
                """, Integer.class, businessId, userId, OWNER, MANAGER);
        if (roleCount == null || roleCount == 0) {
            log.warn("Denied business store update businessId={} userId={} reason=insufficient_role",
                    businessId, userId);
            throw new BusinessStoreForbiddenException();
        }
    }

    private List<String> permissionsFor(String role) {
        return switch (role) {
            case OWNER, MANAGER -> List.of(BusinessMembershipService.LISTING_DRAFT_CREATE);
            default -> List.of();
        };
    }

    private String requiredText(String fieldName, String value, int maxLength) {
        String normalized = optionalText(fieldName, value, maxLength);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return normalized;
    }

    private String optionalText(String fieldName, String value, int maxLength) {
        String trimmed = TextInputs.collapseWhitespaceToNull(value);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " is too long");
        }
        return trimmed;
    }

    private String normalizedSlug(String slug) {
        String normalized = requiredText("Store slug", slug, 100).toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$")) {
            throw new IllegalArgumentException("Store slug is invalid");
        }
        return normalized;
    }

    private String normalizedEmail(String email) {
        String normalized = optionalText("Support email", email, 320);
        if (normalized == null) {
            return null;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Support email is invalid");
        }
        return lower;
    }

    private String normalizedPhone(String phone) {
        String normalized = optionalText("Support phone", phone, 32);
        if (normalized == null) {
            return null;
        }
        if (!normalized.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("Support phone must be E.164 formatted");
        }
        return normalized;
    }

    private String normalizedUrl(String fieldName, String url) {
        String normalized = optionalText(fieldName, url, 2048);
        if (normalized == null) {
            return null;
        }
        if (normalized.startsWith("/api/v1/")) {
            return normalized;
        }
        try {
            URI uri = new URI(normalized);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException(fieldName + " must use http or https");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException(fieldName + " must include a host");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return normalized;
    }
}
