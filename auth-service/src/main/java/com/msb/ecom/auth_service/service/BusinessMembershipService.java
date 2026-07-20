package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.BusinessMembershipResponse;
import com.msb.ecom.auth_service.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BusinessMembershipService {

    public static final String LISTING_DRAFT_CREATE = "LISTING_DRAFT_CREATE";
    public static final String INVENTORY_VIEW = "INVENTORY_VIEW";
    public static final String INVENTORY_MANAGE = "INVENTORY_MANAGE";
    public static final String ORDER_VIEW = "ORDER_VIEW";
    public static final String ORDER_FINANCE_VIEW = "ORDER_FINANCE_VIEW";

    private final AuthService authService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public BusinessMembershipResponse getCurrentMembership(String businessId) {
        User user = authService.ensureUserEntity();
        return jdbcTemplate.query("""
                        select m.business_id, m.user_id, m.role, m.status
                        from business_memberships m
                        join businesses b on b.id = m.business_id
                        where m.business_id = ?
                          and m.user_id = ?
                          and m.status = 'ACTIVE'
                          and b.status = 'ACTIVE'
                        """,
                (rs, rowNum) -> new BusinessMembershipResponse(
                        rs.getString("business_id"),
                        rs.getString("user_id"),
                        rs.getString("role"),
                        rs.getString("status"),
                        permissionsFor(rs.getString("role"))),
                businessId,
                user.getId())
                .stream()
                .findFirst()
                .orElseThrow(BusinessMembershipNotFoundException::new);
    }

    // Keeps role-to-permission expansion in one Auth-owned policy boundary.
    static List<String> permissionsFor(String role) {
        return switch (role) {
            case "OWNER" -> List.of(
                    LISTING_DRAFT_CREATE,
                    INVENTORY_VIEW,
                    INVENTORY_MANAGE,
                    ORDER_VIEW,
                    ORDER_FINANCE_VIEW);
            case "MANAGER" -> List.of(
                    LISTING_DRAFT_CREATE,
                    INVENTORY_VIEW,
                    INVENTORY_MANAGE,
                    ORDER_VIEW);
            default -> List.of();
        };
    }
}
