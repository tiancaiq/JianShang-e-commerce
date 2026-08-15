package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminUserContracts.AvailableAdminCapabilities;
import com.msb.ecom.auth_service.dto.AdminUserContracts.BusinessMembershipSummary;
import com.msb.ecom.auth_service.dto.AdminUserContracts.CreateEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.Detail;
import com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview;
import com.msb.ecom.auth_service.dto.AdminUserContracts.RevokeEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminUserContracts.SearchPage;
import com.msb.ecom.auth_service.dto.AdminUserContracts.SellerSummary;
import com.msb.ecom.auth_service.dto.AdminUserContracts.Summary;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.common.core.validation.FixedLengthIds;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    public static final Set<Scope> OPERATIONAL_SCOPES = Set.of(Scope.USER_BUYING, Scope.USER_SELLING);
    private static final int MAX_PAGE_SIZE = 100;

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AdminAuthorizationService authorizationService;
    private final EnforcementService enforcementService;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public SearchPage search(
            String query,
            String enforcementState,
            Scope scope,
            Instant createdFrom,
            Instant createdTo,
            int page,
            int size,
            String sort) {
        User actor = authService.ensureUserEntity();
        AdminAuthorizationService.AdminAccessSnapshot access = authorizationService.accessFor(actor);
        require(access, AdminPermission.USER_READ);
        boolean pii = access.has(AdminPermission.USER_PII_READ);
        String q = normalizeQuery(query);
        if (!pii && q != null && q.contains("@")) {
            throw new EnforcementExceptions.Validation("Email search requires admin.user.pii.read.");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        String safeSort = normalizedSort(sort);
        SearchSql sql = searchSql(q, pii, normalizedState(enforcementState), normalizedScope(scope),
                createdFrom, createdTo);
        Long total = jdbcTemplate.queryForObject("select count(*) from users u " + sql.where(),
                Long.class, sql.parameters().toArray());
        List<Object> pageParameters = new ArrayList<>(sql.parameters());
        pageParameters.add(safeSize);
        pageParameters.add(safePage * safeSize);
        List<UserRow> rows = jdbcTemplate.query("""
                select u.id, u.email, u.display_name, u.created_at, u.updated_at, u.version,
                       (select p.status from individual_seller_profiles p where p.user_id = u.id) seller_status,
                       (select count(*) from business_memberships bm where bm.user_id = u.id) membership_count,
                       exists(select 1 from user_roles ur where ur.user_id = u.id and ur.role_id in
                           ('PLATFORM_ADMIN','SUPER_ADMIN','TRUST_AND_SAFETY_ADMIN','BUSINESS_REVIEWER',
                            'LISTING_MODERATOR','SUPPORT_ADMIN','USER_RESTRICTOR','BUSINESS_RESTRICTOR',
                            'AUDITOR','AI_ADMIN_AGENT')) platform_admin
                from users u
                """ + sql.where() + " order by " + sortSql(safeSort) + " limit ? offset ?",
                this::userRow, pageParameters.toArray());
        List<Summary> items = rows.stream().map(row -> summary(row, pii)).toList();
        long count = total == null ? 0 : total;
        return new SearchPage(items, safePage, safeSize, count,
                count == 0 ? 0 : (int) Math.ceil((double) count / safeSize), safeSort);
    }

    @Transactional(readOnly = true)
    public Detail detail(String rawUserId) {
        User actor = authService.ensureUserEntity();
        AdminAuthorizationService.AdminAccessSnapshot access = authorizationService.accessFor(actor);
        require(access, AdminPermission.USER_READ);
        String userId = userId(rawUserId);
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("User was not found."));
        boolean pii = access.has(AdminPermission.USER_PII_READ);
        List<Result> actions = enforcementService.actions(TargetType.USER, userId);
        List<Result> active = actions.stream().filter(this::active).toList();
        List<Result> historical = actions.stream().filter(action -> !active(action)).toList();
        boolean platformAdmin = isPlatformAdmin(userId);
        boolean protectedAccount = !"HUMAN".equals(target.getAccountType()) || platformAdmin;
        return new Detail(
                target.getId(), displayName(target.getDisplayName()), safeEmail(target.getEmail(), pii), !pii,
                target.getCreatedAt(), target.getUpdatedAt(), target.getVersion(),
                "OIDC_LINKED", target.getStatus(), target.isEmailVerified(), target.getAccountType(),
                seller(userId), memberships(userId), platformAdmin, strongest(active),
                enforcementService.evaluate(TargetType.USER, userId, Instant.now()), active, historical,
                capabilities(actor, access, target, platformAdmin, protectedAccount));
    }

    @Transactional
    public EnforcementPreview createPreview(String rawUserId, CreateEnforcementRequest request) {
        return create(rawUserId, request, true);
    }

    @Transactional
    public Result createConfirmed(String rawUserId, CreateEnforcementRequest request) {
        return create(rawUserId, request, false).proposedAction();
    }

    @Transactional
    public EnforcementPreview revokePreview(
            String rawUserId,
            String enforcementId,
            RevokeEnforcementRequest request) {
        return revoke(rawUserId, enforcementId, request, true);
    }

    @Transactional
    public Result revokeConfirmed(
            String rawUserId,
            String enforcementId,
            RevokeEnforcementRequest request) {
        return revoke(rawUserId, enforcementId, request, false).proposedAction();
    }

    @Transactional(readOnly = true)
    public List<com.msb.ecom.auth_service.enforcement.EnforcementContracts.TimelineEntry> timeline(String rawUserId) {
        authorizationService.requirePermission(authService.ensureUserEntity(), AdminPermission.AUDIT_READ);
        String userId = userId(rawUserId);
        if (!userRepository.existsById(userId)) {
            throw new EnforcementExceptions.NotFound("User was not found.");
        }
        return enforcementService.timeline(TargetType.USER, userId);
    }

    private EnforcementPreview create(String rawUserId, CreateEnforcementRequest request, boolean dryRun) {
        User actor = authService.ensureUserEntity();
        if (request == null || request.actionType() == null) {
            throw new EnforcementExceptions.Validation("Action type is required.");
        }
        authorizationService.requirePermission(actor, switch (request.actionType()) {
            case RESTRICT -> AdminPermission.USER_RESTRICT;
            case SUSPEND -> AdminPermission.USER_SUSPEND;
            case BAN -> AdminPermission.USER_BAN;
        });
        String userId = userId(rawUserId);
        User target = protectedTarget(actor, userId);
        Set<Scope> scopes = request.actionType() == ActionType.BAN
                ? OPERATIONAL_SCOPES
                : operationalScopes(request.scopes());
        List<Result> existing = enforcementService.actions(TargetType.USER, userId).stream()
                .filter(this::active)
                .filter(action -> action.scopes().stream().anyMatch(scopes::contains))
                .toList();
        Result proposed = enforcementService.create(new CreateCommand(
                TargetType.USER, userId, request.actionType(), scopes, request.reasonCode(), request.reason(), null,
                request.effectiveAt(), request.expiresAt(), request.expectedUserVersion(),
                request.idempotencyKey(), safeMetadata(request.safeMetadata()), dryRun));
        return new EnforcementPreview(proposed, existing, proposed.effectiveRestrictions(),
                warnings(proposed, existing), request.expiresAt() == null, target.getVersion() == request.expectedUserVersion());
    }

    private EnforcementPreview revoke(
            String rawUserId,
            String enforcementId,
            RevokeEnforcementRequest request,
            boolean dryRun) {
        User actor = authService.ensureUserEntity();
        authorizationService.requirePermission(actor, AdminPermission.USER_REINSTATE);
        String userId = userId(rawUserId);
        protectedTarget(actor, userId);
        Result original = enforcementService.actions(TargetType.USER, userId).stream()
                .filter(action -> action.enforcementActionId().equals(enforcementId))
                .findFirst()
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Enforcement action was not found."));
        Result revoked = enforcementService.revoke(new RevokeCommand(
                enforcementId,
                request == null ? null : request.expectedEnforcementVersion(),
                request == null ? null : request.reasonCode(),
                request == null ? null : request.reason(),
                request == null ? null : request.idempotencyKey(),
                request == null ? Map.of() : safeMetadata(request.safeMetadata()),
                dryRun));
        List<Result> overlapping = enforcementService.actions(TargetType.USER, userId).stream()
                .filter(this::active)
                .filter(action -> !action.enforcementActionId().equals(enforcementId))
                .filter(action -> action.scopes().stream().anyMatch(original.scopes()::contains))
                .toList();
        List<String> warnings = revoked.effectiveRestrictions().isEmpty()
                ? List.of()
                : List.of("Other active enforcement remains after this revocation.");
        return new EnforcementPreview(revoked, overlapping, revoked.effectiveRestrictions(), warnings,
                false, true);
    }

    private User protectedTarget(User actor, String userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new EnforcementExceptions.NotFound("User was not found."));
        if (actor.getId().equals(target.getId())) {
            throw new EnforcementExceptions.Validation("Administrators cannot enforce their own marketplace account.");
        }
        if (!"HUMAN".equals(target.getAccountType())) {
            throw new EnforcementExceptions.Validation("Service and automation accounts cannot be enforced here.");
        }
        if (isPlatformAdmin(userId) && !authorizationService.isSuperAdmin(actor)) {
            throw new EnforcementExceptions.Validation(
                    "Only a SUPER_ADMIN may enforce another platform administrator's marketplace capabilities.");
        }
        return target;
    }

    private AvailableAdminCapabilities capabilities(
            User actor,
            AdminAuthorizationService.AdminAccessSnapshot access,
            User target,
            boolean platformAdmin,
            boolean protectedAccount) {
        boolean self = actor.getId().equals(target.getId());
        boolean permittedTarget = !self && "HUMAN".equals(target.getAccountType())
                && (!platformAdmin || access.roles().contains("SUPER_ADMIN"));
        String reason = self ? "You cannot enforce your own marketplace account."
                : !"HUMAN".equals(target.getAccountType()) ? "Service and automation accounts are protected."
                : platformAdmin && !access.roles().contains("SUPER_ADMIN")
                        ? "Only a SUPER_ADMIN may enforce another administrator."
                        : null;
        return new AvailableAdminCapabilities(
                access.has(AdminPermission.USER_READ), access.has(AdminPermission.USER_PII_READ),
                permittedTarget && access.has(AdminPermission.USER_RESTRICT),
                permittedTarget && access.has(AdminPermission.USER_SUSPEND),
                permittedTarget && access.has(AdminPermission.USER_BAN),
                permittedTarget && access.has(AdminPermission.USER_REINSTATE),
                self, protectedAccount, reason, OPERATIONAL_SCOPES);
    }

    private Summary summary(UserRow row, boolean pii) {
        List<Result> active = enforcementService.actions(TargetType.USER, row.id()).stream()
                .filter(this::active).toList();
        LinkedHashSet<Scope> scopes = new LinkedHashSet<>();
        active.forEach(action -> scopes.addAll(action.scopes()));
        return new Summary(row.id(), displayName(row.displayName()), safeEmail(row.email(), pii), !pii,
                row.createdAt(), row.updatedAt(), row.version(),
                row.sellerStatus() == null ? "NOT_ACTIVATED" : row.sellerStatus(), row.membershipCount(),
                row.platformAdmin(), strongest(active), Set.copyOf(scopes), active.size());
    }

    private SellerSummary seller(String userId) {
        return jdbcTemplate.query("""
                select status, created_at, updated_at, version
                from individual_seller_profiles where user_id = ?
                """, (rs, rowNum) -> new SellerSummary(rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version")), userId).stream().findFirst().orElse(null);
    }

    private List<BusinessMembershipSummary> memberships(String userId) {
        return jdbcTemplate.query("""
                select bm.business_id, b.legal_name, b.status business_status,
                       bm.role membership_role, bm.status membership_status
                from business_memberships bm
                join businesses b on b.id = bm.business_id
                where bm.user_id = ?
                order by b.legal_name, bm.business_id
                """, (rs, rowNum) -> new BusinessMembershipSummary(
                rs.getString("business_id"), rs.getString("legal_name"), rs.getString("business_status"),
                rs.getString("membership_role"), rs.getString("membership_status")), userId);
    }

    private boolean isPlatformAdmin(String userId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from user_roles
                where user_id = ? and role_id in
                    ('PLATFORM_ADMIN','SUPER_ADMIN','TRUST_AND_SAFETY_ADMIN','BUSINESS_REVIEWER',
                     'LISTING_MODERATOR','SUPPORT_ADMIN','USER_RESTRICTOR','BUSINESS_RESTRICTOR',
                     'AUDITOR','AI_ADMIN_AGENT')
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    private SearchSql searchSql(
            String q,
            boolean pii,
            String state,
            Scope scope,
            Instant createdFrom,
            Instant createdTo) {
        StringBuilder where = new StringBuilder(" where 1=1\n");
        List<Object> parameters = new ArrayList<>();
        if (q != null) {
            where.append(" and (u.id = ? or lower(coalesce(u.display_name,'')) like ? escape '!' ");
            parameters.add(q);
            parameters.add("%" + escapedLike(q.toLowerCase(Locale.ROOT)) + "%");
            if (pii) {
                where.append(" or lower(coalesce(u.email,'')) like ? escape '!' ");
                parameters.add("%" + escapedLike(q.toLowerCase(Locale.ROOT)) + "%");
            }
            where.append(")\n");
        }
        if (createdFrom != null) {
            where.append(" and u.created_at >= ?\n");
            parameters.add(Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            where.append(" and u.created_at < ?\n");
            parameters.add(Timestamp.from(createdTo));
        }
        if (scope != null) {
            where.append("""
                     and exists (
                       select 1 from enforcement_actions ea
                       join enforcement_action_scopes eas on eas.enforcement_action_id = ea.id
                       where ea.target_type = 'USER' and ea.target_id = u.id and eas.scope = ?
                         and ea.revoked_at is null and ea.effective_at <= current_timestamp(6)
                         and (ea.expires_at is null or ea.expires_at > current_timestamp(6)))
                    """);
            parameters.add(scope.name());
        }
        if (state != null) {
            String severity = """
                    (select max(case ea.action_type when 'BAN' then 3 when 'SUSPEND' then 2 else 1 end)
                     from enforcement_actions ea where ea.target_type = 'USER' and ea.target_id = u.id
                       and ea.revoked_at is null and ea.effective_at <= current_timestamp(6)
                       and (ea.expires_at is null or ea.expires_at > current_timestamp(6)))
                    """;
            if ("CLEAR".equals(state)) {
                where.append(" and ").append(severity).append(" is null\n");
            } else {
                where.append(" and ").append(severity).append(" = ?\n");
                parameters.add(switch (state) { case "BANNED" -> 3; case "SUSPENDED" -> 2; default -> 1; });
            }
        }
        return new SearchSql(where.toString(), parameters);
    }

    private Set<Scope> operationalScopes(Set<Scope> scopes) {
        if (scopes == null || scopes.isEmpty() || !OPERATIONAL_SCOPES.containsAll(scopes)) {
            throw new EnforcementExceptions.Validation("Select at least one operational user scope.");
        }
        return Set.copyOf(scopes);
    }

    private Scope normalizedScope(Scope scope) {
        if (scope != null && !OPERATIONAL_SCOPES.contains(scope)) {
            throw new EnforcementExceptions.Validation("The selected scope is not operational.");
        }
        return scope;
    }

    private String normalizedState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String normalized = state.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CLEAR", "RESTRICTED", "SUSPENDED", "BANNED").contains(normalized)) {
            throw new EnforcementExceptions.Validation("Enforcement state filter is invalid.");
        }
        return normalized;
    }

    private String normalizedSort(String sort) {
        String value = sort == null || sort.isBlank() ? "createdAt,desc" : sort.trim();
        if (!Set.of("createdAt,desc", "createdAt,asc", "updatedAt,desc", "updatedAt,asc",
                "userId,asc", "userId,desc").contains(value)) {
            throw new EnforcementExceptions.Validation("Sort is invalid.");
        }
        return value;
    }

    private String sortSql(String sort) {
        return switch (sort) {
            case "createdAt,asc" -> "u.created_at asc, u.id asc";
            case "updatedAt,desc" -> "u.updated_at desc, u.id desc";
            case "updatedAt,asc" -> "u.updated_at asc, u.id asc";
            case "userId,asc" -> "u.id asc";
            case "userId,desc" -> "u.id desc";
            default -> "u.created_at desc, u.id desc";
        };
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String value = query.trim().replaceAll("\\s+", " ");
        if (value.length() > 320) {
            throw new EnforcementExceptions.Validation("Search query is too long.");
        }
        return value;
    }

    private String userId(String value) {
        return FixedLengthIds.requireTrimmed("User ID", value, 26);
    }

    private boolean active(Result action) {
        return action.lifecycleState() == LifecycleState.ACTIVE;
    }

    private ActionType strongest(List<Result> actions) {
        return actions.stream().map(Result::actionType)
                .max(Comparator.comparingInt(ActionType::severity)).orElse(null);
    }

    private List<String> warnings(Result proposed, List<Result> existing) {
        List<String> warnings = new ArrayList<>();
        if (proposed.expiresAt() == null) {
            warnings.add("This action is indefinite until explicitly revoked.");
        }
        if (existing.stream().anyMatch(action -> action.actionType().severity() > proposed.actionType().severity())) {
            warnings.add("A stronger active action already controls one or more selected scopes.");
        }
        return List.copyOf(warnings);
    }

    private Map<String, String> safeMetadata(Map<String, String> metadata) {
        return metadata == null ? Map.of() : metadata;
    }

    private void require(AdminAuthorizationService.AdminAccessSnapshot access, AdminPermission permission) {
        if (!access.has(permission)) {
            throw new BusinessApplicationForbiddenException();
        }
    }

    private String safeEmail(String email, boolean pii) {
        if (email == null || email.isBlank()) {
            return null;
        }
        if (pii) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        String visible = local.substring(0, 1);
        int dot = domain.lastIndexOf('.');
        String suffix = dot >= 0 ? domain.substring(dot) : "";
        return visible + "***@***" + suffix;
    }

    private String displayName(String displayName) {
        return displayName == null || displayName.isBlank() ? "Marketplace user" : displayName.trim();
    }

    private String escapedLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private UserRow userRow(ResultSet rs, int rowNum) throws SQLException {
        return new UserRow(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"), rs.getString("seller_status"), rs.getLong("membership_count"),
                rs.getBoolean("platform_admin"));
    }

    private record SearchSql(String where, List<Object> parameters) {
    }

    private record UserRow(
            String id,
            String email,
            String displayName,
            Instant createdAt,
            Instant updatedAt,
            long version,
            String sellerStatus,
            long membershipCount,
            boolean platformAdmin) {
    }
}
