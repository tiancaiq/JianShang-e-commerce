package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminDashboardSummaryResponse;
import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.BusinessIdentityLabelResponse;
import com.msb.ecom.auth_service.dto.PlatformAdminResponse;
import com.msb.ecom.auth_service.dto.PublicBusinessStoreSearchResponse;
import com.msb.ecom.auth_service.dto.UserIdentityLabelResponse;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.core.validation.TextInputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuthorizationService {

    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";
    private static final int MAX_LABEL_IDS_PER_TYPE = 50;

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public PlatformAdminResponse requireCurrentAdmin() {
        User user = authService.ensureUserEntity();
        AdminAccess access = resolveAccess(user);
        if (access.roles().isEmpty()) {
            deny(user.getId(), "missing_admin_role", null);
        }
        return new PlatformAdminResponse(
                user.getId(),
                PLATFORM_ADMIN_ROLE,
                access.roles(),
                access.permissions(),
                user.getStatus());
    }

    @Transactional
    public String requireCurrentAdminPermission(AdminPermission permission) {
        User user = authService.ensureUserEntity();
        return requirePermission(user, permission);
    }

    public String requirePermission(User user, AdminPermission permission) {
        AdminAccess access = resolveAccess(user);
        if (!access.permissions().contains(permission.id())) {
            deny(user.getId(), "missing_permission", permission.id());
        }
        return user.getId();
    }

    public AdminAccessSnapshot accessFor(User user) {
        AdminAccess access = resolveAccess(user);
        return new AdminAccessSnapshot(access.roles(), access.permissions());
    }

    public boolean isPlatformAdmin(User user) {
        return !resolveAccess(user).roles().isEmpty();
    }

    public boolean isSuperAdmin(User user) {
        return resolveAccess(user).roles().contains(SUPER_ADMIN_ROLE);
    }

    @Transactional(readOnly = true)
    // Provides ADM-00 dashboard counts owned by the identity/business schema.
    public AdminDashboardSummaryResponse dashboardSummary() {
        User user = authService.ensureUserEntity();
        requirePermission(user, AdminPermission.DASHBOARD_READ);
        Long pendingBusinessApplications = jdbcTemplate.queryForObject("""
                select count(*)
                from business_applications
                where status in ('PENDING_VERIFICATION', 'UNDER_REVIEW')
                """, Long.class);
        return new AdminDashboardSummaryResponse(
                pendingBusinessApplications == null ? 0 : pendingBusinessApplications);
    }

    @Transactional(readOnly = true)
    // Returns admin-safe display labels so moderation UIs do not expose raw IDs as primary text.
    public AdminIdentityLabelsResponse identityLabels(Set<String> userIds, Set<String> businessIds) {
        User user = authService.ensureUserEntity();
        requirePermission(user, AdminPermission.AUDIT_READ);
        return labelResponse(userIds, businessIds, false);
    }

    @Transactional(readOnly = true)
    // Public listing cards need only seller display labels; raw identity IDs stay owned by auth-service.
    public AdminIdentityLabelsResponse publicSellerLabels(Set<String> userIds, Set<String> businessIds) {
        return labelResponse(userIds, businessIds, true);
    }

    @Transactional(readOnly = true)
    // Returns only public-safe active business/store records for storefront search and product-service visibility revalidation.
    public List<PublicBusinessStoreSearchResponse> publicBusinessStores(
            String query,
            Set<String> businessIds,
            Set<String> storeIds) {
        String normalizedQuery = TextInputs.collapseWhitespaceToNull(query);
        if (normalizedQuery != null && normalizedQuery.length() > 120) {
            throw new IllegalArgumentException("Store search query is too long.");
        }
        Set<String> normalizedBusinessIds = normalizedIds(businessIds);
        Set<String> normalizedStoreIds = normalizedIds(storeIds);
        if (normalizedQuery == null && normalizedBusinessIds.isEmpty() && normalizedStoreIds.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder("""
                select b.id as business_id,
                       s.id as store_id,
                       s.name as store_name,
                       b.legal_name as business_legal_name
                from businesses b
                join stores s on s.business_id = b.id
                where b.status = 'ACTIVE'
                  and s.status = 'ACTIVE'
                """);
        List<Object> parameters = new ArrayList<>();

        if (normalizedQuery != null) {
            String pattern = "%" + escapedLike(normalizedQuery.toLowerCase(Locale.ROOT)) + "%";
            sql.append("""
                    and (
                         lower(s.name) like ? escape '!'
                      or lower(b.legal_name) like ? escape '!'
                    )
                    """);
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (!normalizedBusinessIds.isEmpty()) {
            sql.append(" and b.id in (%s)\n".formatted(placeholders(normalizedBusinessIds.size())));
            parameters.addAll(normalizedBusinessIds);
        }
        if (!normalizedStoreIds.isEmpty()) {
            sql.append(" and s.id in (%s)\n".formatted(placeholders(normalizedStoreIds.size())));
            parameters.addAll(normalizedStoreIds);
        }

        sql.append(" order by s.name, b.id limit 50");
        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> new PublicBusinessStoreSearchResponse(
                        rs.getString("business_id"),
                        rs.getString("store_id"),
                        rs.getString("store_name"),
                        rs.getString("business_legal_name")),
                parameters.toArray());
    }

    private AdminIdentityLabelsResponse labelResponse(Set<String> userIds, Set<String> businessIds, boolean publicOnly) {
        Set<String> normalizedUserIds = normalizedIds(userIds);
        Set<String> normalizedBusinessIds = normalizedIds(businessIds);

        List<UserIdentityLabelResponse> users = normalizedUserIds.isEmpty()
                ? List.of()
                : jdbcTemplate.query("""
                        select id,
                               coalesce(nullif(trim(display_name), ''), 'Marketplace user') as display_name,
                               public_handle,
                               avatar_url
                        from users
                        where id in (%s)
                        %s
                        order by id
                        """.formatted(placeholders(normalizedUserIds.size()), publicOnly ? "and status = 'ACTIVE'" : ""),
                        (rs, rowNum) -> new UserIdentityLabelResponse(
                                rs.getString("id"),
                                rs.getString("display_name"),
                                rs.getString("public_handle"),
                                rs.getString("avatar_url")),
                        normalizedUserIds.toArray());
        List<BusinessIdentityLabelResponse> businesses = normalizedBusinessIds.isEmpty()
                ? List.of()
                : jdbcTemplate.query("""
                        select b.id, b.legal_name,
                               s.id as store_id, s.slug as store_slug, s.name as store_name,
                               a.public_city, a.public_region,
                               case when b.status = 'ACTIVE' then true else false end as verified
                        from businesses b
                        join business_applications a on a.id = b.application_id
                        left join stores s on s.business_id = b.id and s.status = 'ACTIVE'
                        where b.id in (%s)
                        %s
                        order by b.id
                        """.formatted(placeholders(normalizedBusinessIds.size()), publicOnly ? "and b.status = 'ACTIVE'" : ""),
                        (rs, rowNum) -> new BusinessIdentityLabelResponse(
                                rs.getString("id"),
                                rs.getString("legal_name"),
                                rs.getString("store_id"),
                                rs.getString("store_slug"),
                                rs.getString("store_name"),
                                rs.getString("public_city"),
                                rs.getString("public_region"),
                                rs.getBoolean("verified")),
                        normalizedBusinessIds.toArray());

        return new AdminIdentityLabelsResponse(users, businesses);
    }

    private AdminAccess resolveAccess(User user) {
        if (!"ACTIVE".equals(user.getStatus())) {
            deny(user.getId(), "inactive_account", null);
        }
        List<String> assignedRoles = jdbcTemplate.queryForList("""
                select distinct ur.role_id
                from user_roles ur
                where ur.user_id = ?
                  and ur.role_id in (
                    'PLATFORM_ADMIN', 'SUPER_ADMIN', 'TRUST_AND_SAFETY_ADMIN',
                    'BUSINESS_REVIEWER', 'LISTING_MODERATOR', 'SUPPORT_ADMIN', 'USER_RESTRICTOR',
                    'BUSINESS_RESTRICTOR',
                    'AUDITOR', 'AI_ADMIN_AGENT'
                  )
                order by ur.role_id
                """, String.class, user.getId());
        LinkedHashSet<String> effectiveRoles = new LinkedHashSet<>(assignedRoles);
        if (effectiveRoles.remove(PLATFORM_ADMIN_ROLE)) {
            effectiveRoles.add(SUPER_ADMIN_ROLE);
        }
        List<String> permissions = assignedRoles.isEmpty()
                ? List.of()
                : jdbcTemplate.queryForList("""
                        select distinct arp.permission_id
                        from user_roles ur
                        join admin_role_permissions arp on arp.role_id = ur.role_id
                        where ur.user_id = ?
                        order by arp.permission_id
                        """, String.class, user.getId());
        return new AdminAccess(List.copyOf(effectiveRoles), permissions);
    }

    private void deny(String userId, String reason, String permission) {
        log.warn("Denied admin authorization userId={} reason={} permission={}", userId, reason, permission);
        throw new BusinessApplicationForbiddenException();
    }

    private record AdminAccess(List<String> roles, List<String> permissions) {
    }

    public record AdminAccessSnapshot(List<String> roles, List<String> permissions) {
        public boolean has(AdminPermission permission) {
            return permissions.contains(permission.id());
        }
    }

    private Set<String> normalizedIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            normalized.add(FixedLengthIds.requireTrimmed("ID", id, 26));
        }
        if (normalized.size() > MAX_LABEL_IDS_PER_TYPE) {
            throw new IllegalArgumentException("At most 50 IDs can be resolved per label type.");
        }
        return normalized;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private String escapedLike(String value) {
        return value
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
