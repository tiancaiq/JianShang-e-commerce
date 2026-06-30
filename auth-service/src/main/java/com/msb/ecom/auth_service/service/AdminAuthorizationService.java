package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminDashboardSummaryResponse;
import com.msb.ecom.auth_service.dto.AdminIdentityLabelsResponse;
import com.msb.ecom.auth_service.dto.BusinessIdentityLabelResponse;
import com.msb.ecom.auth_service.dto.PlatformAdminResponse;
import com.msb.ecom.auth_service.dto.UserIdentityLabelResponse;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuthorizationService {

    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public PlatformAdminResponse requireCurrentPlatformAdmin() {
        User user = authService.ensureUserEntity();
        requirePlatformAdmin(user.getId());
        return new PlatformAdminResponse(user.getId(), PLATFORM_ADMIN_ROLE);
    }

    @Transactional(readOnly = true)
    // Provides ADM-00 dashboard counts owned by the identity/business schema.
    public AdminDashboardSummaryResponse dashboardSummary() {
        User user = authService.ensureUserEntity();
        requirePlatformAdmin(user.getId());
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
        requirePlatformAdmin(user.getId());
        Set<String> normalizedUserIds = normalizedIds(userIds);
        Set<String> normalizedBusinessIds = normalizedIds(businessIds);

        List<UserIdentityLabelResponse> users = normalizedUserIds.isEmpty()
                ? List.of()
                : jdbcTemplate.query("""
                        select id, display_name
                        from users
                        where id in (%s)
                        order by id
                        """.formatted(placeholders(normalizedUserIds.size())),
                        (rs, rowNum) -> new UserIdentityLabelResponse(
                                rs.getString("id"),
                                rs.getString("display_name")),
                        normalizedUserIds.toArray());
        List<BusinessIdentityLabelResponse> businesses = normalizedBusinessIds.isEmpty()
                ? List.of()
                : jdbcTemplate.query("""
                        select id, legal_name
                        from businesses
                        where id in (%s)
                        order by id
                        """.formatted(placeholders(normalizedBusinessIds.size())),
                        (rs, rowNum) -> new BusinessIdentityLabelResponse(
                                rs.getString("id"),
                                rs.getString("legal_name")),
                        normalizedBusinessIds.toArray());

        return new AdminIdentityLabelsResponse(users, businesses);
    }

    private void requirePlatformAdmin(String userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from user_roles
                where user_id = ? and role_id = ?
                """, Integer.class, userId, PLATFORM_ADMIN_ROLE);
        if (count == null || count == 0) {
            log.warn("Denied platform admin authorization userId={} reason=missing_role", userId);
            throw new BusinessApplicationForbiddenException();
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
        return normalized;
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
