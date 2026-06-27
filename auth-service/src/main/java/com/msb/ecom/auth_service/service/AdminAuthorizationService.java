package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.PlatformAdminResponse;
import com.msb.ecom.auth_service.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
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
            throw new BusinessApplicationForbiddenException();
        }
        return new PlatformAdminResponse(user.getId(), PLATFORM_ADMIN_ROLE);
    }
}
