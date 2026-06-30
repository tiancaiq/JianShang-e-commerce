package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.PlatformAdminResponse;
import com.msb.ecom.auth_service.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from user_roles
                where user_id = ? and role_id = ?
                """, Integer.class, user.getId(), PLATFORM_ADMIN_ROLE);
        if (count == null || count == 0) {
            log.warn("Denied platform admin authorization userId={} reason=missing_role", user.getId());
            throw new BusinessApplicationForbiddenException();
        }
        return new PlatformAdminResponse(user.getId(), PLATFORM_ADMIN_ROLE);
    }
}
