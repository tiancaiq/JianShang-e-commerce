package com.msb.ecom.product_service.service;

import com.msb.ecom.product_service.dto.InternalAppealListingContext;
import com.msb.ecom.product_service.model.ListingNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AppealListingContextService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    // Provides the minimal Product-owned enforcement and ownership context needed by Auth-owned appeals.
    public InternalAppealListingContext byActionId(String actionId) {
        return jdbc.query(BASE_SELECT + " where ea.id = ?", this::row, actionId).stream().findFirst()
                .map(this::withScopes).orElseThrow(ListingNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<InternalAppealListingContext> activeForActor(String userId, Set<String> businessIds) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        String businessClause = "";
        if (businessIds != null && !businessIds.isEmpty()) {
            businessClause = " or (l.seller_type = 'BUSINESS' and l.business_id in ("
                    + String.join(",", businessIds.stream().map(ignored -> "?").toList()) + "))";
            businessIds.stream().sorted().forEach(parameters::add);
        }
        Instant now = clock.instant();
        parameters.add(Timestamp.from(now));
        parameters.add(Timestamp.from(now));
        String sql = BASE_SELECT + """
                 where ((l.seller_type = 'INDIVIDUAL' and l.individual_seller_user_id = ?)%s)
                   and ea.revoked_at is null and ea.effective_at <= ?
                   and (ea.expires_at is null or ea.expires_at > ?)
                 order by ea.created_at desc, ea.id desc
                """.formatted(businessClause);
        return jdbc.query(sql, this::row, parameters.toArray()).stream().map(this::withScopes).toList();
    }

    private InternalAppealListingContext row(ResultSet rs, int row) throws SQLException {
        Instant now = clock.instant();
        Timestamp expires = rs.getTimestamp("expires_at");
        Timestamp revoked = rs.getTimestamp("revoked_at");
        String lifecycle = revoked != null ? "REVOKED"
                : expires != null && !expires.toInstant().isAfter(now) ? "EXPIRED" : "ACTIVE";
        return new InternalAppealListingContext(
                rs.getString("enforcement_action_id"), rs.getString("listing_id"),
                rs.getString("title"), rs.getString("seller_type"),
                rs.getString("individual_seller_user_id"), rs.getString("business_id"),
                rs.getString("listing_status"), rs.getLong("listing_version"),
                rs.getString("action_type"), List.of(), lifecycle,
                rs.getTimestamp("effective_at").toInstant(),
                expires == null ? null : expires.toInstant(), rs.getLong("enforcement_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("reason_code"),
                rs.getString("reason"), rs.getString("case_id"), null);
    }

    private InternalAppealListingContext withScopes(InternalAppealListingContext value) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>(jdbc.queryForList(
                "select scope from enforcement_action_scopes where enforcement_action_id = ? order by scope",
                String.class, value.enforcementActionId()));
        return new InternalAppealListingContext(value.enforcementActionId(), value.listingId(),
                value.safeListingLabel(), value.sellerType(), value.individualSellerUserId(), value.businessId(),
                value.listingStatus(), value.listingVersion(), value.actionType(), List.copyOf(scopes),
                value.lifecycleState(), value.effectiveAt(), value.expiresAt(), value.enforcementVersion(),
                value.createdAt(), value.reasonCode(), value.reason(), value.caseId(),
                effectiveState(value.listingId()));
    }

    private String effectiveState(String listingId) {
        Instant now = clock.instant();
        Integer severity = jdbc.queryForObject("""
                select coalesce(max(case action_type when 'SUSPEND' then 2 else 1 end), 0)
                  from enforcement_actions
                 where target_type = 'LISTING' and target_id = ? and revoked_at is null
                   and effective_at <= ? and (expires_at is null or expires_at > ?)
                """, Integer.class, listingId, Timestamp.from(now), Timestamp.from(now));
        if (severity == null || severity == 0) return "CLEAR";
        return severity >= 2 ? "SUSPENDED" : "RESTRICTED";
    }

    private static final String BASE_SELECT = """
            select ea.id enforcement_action_id, ea.target_id listing_id, ea.action_type,
                   ea.version enforcement_version, ea.effective_at, ea.expires_at, ea.revoked_at,
                   ea.created_at, ea.reason_code, ea.reason, ea.case_id,
                   l.title, l.seller_type, l.individual_seller_user_id, l.business_id,
                   l.status listing_status, l.version listing_version
            from enforcement_actions ea
            join listings l on l.id = ea.target_id
            """;
}
