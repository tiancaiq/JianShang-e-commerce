package com.msb.ecom.auth_service.service;

import com.msb.ecom.auth_service.dto.AdminBusinessContracts.AvailableAdminCapabilities;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.CreateEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.Detail;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.EnforcementPreview;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.ListingSummary;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.MembershipSummary;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.OrderSummary;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.OwnerSummary;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.RevokeEnforcementRequest;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.SearchPage;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.Summary;
import com.msb.ecom.auth_service.dto.AdminBusinessContracts.TimelineEntry;
import com.msb.ecom.auth_service.dto.AdminTimelineEntryResponse;
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
import com.msb.ecom.auth_service.repository.BusinessApplicationTimelineRepository;
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
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AdminBusinessService {

    public static final Set<Scope> OPERATIONAL_SCOPES = BusinessCapabilityService.OPERATIONAL_SCOPES;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuthService authService;
    private final AdminAuthorizationService authorizationService;
    private final EnforcementService enforcementService;
    private final ProductBusinessListingSummaryClient listingSummaryClient;
    private final BusinessApplicationTimelineRepository applicationTimelineRepository;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public SearchPage search(String query, String businessState, String enforcementState, Scope scope,
                             Instant createdFrom, Instant createdTo, int page, int size, String sort) {
        AdminAuthorizationService.AdminAccessSnapshot access = currentAccess(AdminPermission.BUSINESS_READ);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        String safeSort = normalizedSort(sort);
        SearchSql sql = searchSql(normalizeQuery(query), normalizedBusinessState(businessState),
                normalizedEnforcementState(enforcementState), normalizedScope(scope), createdFrom, createdTo);
        Long total = jdbcTemplate.queryForObject("select count(*) from businesses b " + sql.where(),
                Long.class, sql.parameters().toArray());
        List<Object> parameters = new ArrayList<>(sql.parameters());
        parameters.add(safeSize);
        parameters.add(safePage * safeSize);
        List<BusinessRow> rows = jdbcTemplate.query("""
                select b.id, b.legal_name, b.status, b.application_id, b.created_at, b.updated_at, b.version,
                       coalesce(s.name, b.legal_name) display_name, s.status store_status,
                       (select bm.user_id from business_memberships bm
                         where bm.business_id = b.id and bm.role = 'OWNER'
                         order by case bm.status when 'ACTIVE' then 0 else 1 end, bm.created_at limit 1) owner_user_id,
                       (select count(*) from business_memberships bm where bm.business_id = b.id) member_count
                from businesses b left join stores s on s.business_id = b.id
                """ + sql.where() + " order by " + sortSql(safeSort) + " limit ? offset ?",
                this::businessRow, parameters.toArray());
        Set<String> ids = rows.stream().map(BusinessRow::id).collect(java.util.stream.Collectors.toSet());
        Map<String, ProductBusinessListingSummaryClient.Summary> listingSummaries = listingSummaryClient.summaries(ids);
        List<Summary> items = rows.stream().map(row -> summary(row, listingSummaries.get(row.id()))).toList();
        long count = total == null ? 0 : total;
        return new SearchPage(items, safePage, safeSize, count,
                count == 0 ? 0 : (int) Math.ceil((double) count / safeSize), safeSort);
    }

    @Transactional(readOnly = true)
    public Detail detail(String rawBusinessId) {
        AdminAuthorizationService.AdminAccessSnapshot access = currentAccess(AdminPermission.BUSINESS_READ);
        String businessId = businessId(rawBusinessId);
        BusinessRow row = findBusiness(businessId);
        List<Result> actions = enforcementService.actions(TargetType.BUSINESS, businessId);
        List<Result> active = actions.stream().filter(this::active).toList();
        List<Result> historical = actions.stream().filter(action -> !active(action)).toList();
        List<MembershipSummary> memberships = memberships(businessId);
        OwnerSummary owner = memberships.stream().filter(member -> "OWNER".equals(member.role())).findFirst()
                .map(member -> new OwnerSummary(member.userId(), member.safeDisplayName(),
                        member.membershipState(), member.strongestUserEnforcement())).orElse(null);
        ProductBusinessListingSummaryClient.Summary listing = listingSummaryClient.summaries(Set.of(businessId))
                .get(businessId);
        return new Detail(row.id(), row.displayName(), row.legalName(), row.status(), row.storeStatus(),
                verificationState(row.applicationId()), row.applicationId(), row.createdAt(), row.updatedAt(),
                row.version(), owner, memberships, listingSummary(listing),
                new OrderSummary(false, null, null,
                        "Order counts are not copied into Auth Service; existing-order operations remain unchanged."),
                strongest(active), enforcementService.evaluate(TargetType.BUSINESS, businessId, Instant.now()),
                active, historical, capabilities(access));
    }

    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(String rawBusinessId) {
        currentAccess(AdminPermission.AUDIT_READ);
        BusinessRow business = findBusiness(businessId(rawBusinessId));
        Stream<TimelineEntry> verification = applicationTimelineRepository.findByApplicationId(business.applicationId())
                .stream().map(event -> verificationTimeline(business.id(), event));
        Stream<TimelineEntry> enforcement = enforcementService.timeline(TargetType.BUSINESS, business.id()).stream()
                .map(event -> new TimelineEntry(event.eventId(), event.occurredAt(), event.eventType(),
                        event.actorType(), event.actorId(), event.actorDisplayName(), event.source().name(),
                        event.targetType().name(), event.targetId(), event.enforcementActionId(), event.actionType(),
                        event.scopes(), event.previousState(), event.newState(), event.reasonCode(), event.reason(),
                        event.caseId(), event.correlationId(), event.requestId(), event.safeMetadata()));
        return Stream.concat(verification, enforcement)
                .sorted(Comparator.comparing(TimelineEntry::occurredAt).thenComparing(TimelineEntry::eventId))
                .toList();
    }

    @Transactional
    public EnforcementPreview createPreview(String businessId, CreateEnforcementRequest request) {
        return create(businessId, request, true);
    }

    @Transactional
    public Result createConfirmed(String businessId, CreateEnforcementRequest request) {
        return create(businessId, request, false).proposedAction();
    }

    @Transactional
    public EnforcementPreview revokePreview(String businessId, String enforcementId,
                                             RevokeEnforcementRequest request) {
        return revoke(businessId, enforcementId, request, true);
    }

    @Transactional
    public Result revokeConfirmed(String businessId, String enforcementId, RevokeEnforcementRequest request) {
        return revoke(businessId, enforcementId, request, false).proposedAction();
    }

    private EnforcementPreview create(String rawBusinessId, CreateEnforcementRequest request, boolean dryRun) {
        User actor = authService.ensureUserEntity();
        if (request == null || request.actionType() == null) {
            throw new EnforcementExceptions.Validation("Action type is required.");
        }
        authorizationService.requirePermission(actor, switch (request.actionType()) {
            case RESTRICT -> AdminPermission.BUSINESS_RESTRICT;
            case SUSPEND -> AdminPermission.BUSINESS_SUSPEND;
            case BAN -> AdminPermission.BUSINESS_BAN;
        });
        String businessId = businessId(rawBusinessId);
        BusinessRow target = findBusiness(businessId);
        Set<Scope> scopes = request.actionType() == ActionType.BAN
                ? OPERATIONAL_SCOPES : operationalScopes(request.scopes());
        List<Result> existing = enforcementService.actions(TargetType.BUSINESS, businessId).stream()
                .filter(this::active).filter(action -> action.scopes().stream().anyMatch(scopes::contains)).toList();
        Result proposed = enforcementService.create(new CreateCommand(TargetType.BUSINESS, businessId,
                request.actionType(), scopes, request.reasonCode(), request.reason(), null, request.effectiveAt(),
                request.expiresAt(), request.expectedBusinessVersion(), request.idempotencyKey(),
                safeMetadata(request.safeMetadata()), dryRun));
        boolean versionCurrent = request.expectedBusinessVersion() != null
                && target.version() == request.expectedBusinessVersion();
        return new EnforcementPreview(proposed, existing, proposed.effectiveRestrictions(), warnings(proposed, existing),
                request.expiresAt() == null, versionCurrent, impactSummary(scopes));
    }

    private EnforcementPreview revoke(String rawBusinessId, String enforcementId,
                                       RevokeEnforcementRequest request, boolean dryRun) {
        User actor = authService.ensureUserEntity();
        authorizationService.requirePermission(actor, AdminPermission.BUSINESS_REINSTATE);
        String businessId = businessId(rawBusinessId);
        findBusiness(businessId);
        Result original = enforcementService.actions(TargetType.BUSINESS, businessId).stream()
                .filter(action -> action.enforcementActionId().equals(enforcementId)).findFirst()
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Business enforcement action was not found."));
        Result revoked = enforcementService.revoke(new RevokeCommand(enforcementId,
                request == null ? null : request.expectedEnforcementVersion(),
                request == null ? null : request.reasonCode(), request == null ? null : request.reason(),
                request == null ? null : request.idempotencyKey(),
                request == null ? Map.of() : safeMetadata(request.safeMetadata()), dryRun));
        List<Result> overlapping = enforcementService.actions(TargetType.BUSINESS, businessId).stream()
                .filter(this::active).filter(action -> !action.enforcementActionId().equals(enforcementId))
                .filter(action -> action.scopes().stream().anyMatch(original.scopes()::contains)).toList();
        List<String> warnings = revoked.effectiveRestrictions().isEmpty() ? List.of()
                : List.of("Other active business enforcement remains after this revocation.");
        return new EnforcementPreview(revoked, overlapping, revoked.effectiveRestrictions(), warnings, false, true,
                impactSummary(original.scopes()));
    }

    private Summary summary(BusinessRow row, ProductBusinessListingSummaryClient.Summary listing) {
        List<Result> active = enforcementService.actions(TargetType.BUSINESS, row.id()).stream()
                .filter(this::active).toList();
        LinkedHashSet<Scope> scopes = new LinkedHashSet<>();
        active.forEach(action -> scopes.addAll(action.scopes()));
        return new Summary(row.id(), row.displayName(), row.status(), row.createdAt(), row.updatedAt(), row.version(),
                row.ownerUserId(), row.memberCount(), listing == null ? 0 : listing.activeCount(), strongest(active),
                Set.copyOf(scopes), active.size());
    }

    private List<MembershipSummary> memberships(String businessId) {
        return jdbcTemplate.query("""
                select bm.user_id, coalesce(nullif(trim(u.display_name), ''), 'Marketplace user') display_name,
                       bm.role, bm.status, bm.created_at
                from business_memberships bm join users u on u.id = bm.user_id
                where bm.business_id = ? order by case bm.role when 'OWNER' then 0 when 'MANAGER' then 1 else 2 end,
                         bm.created_at, bm.user_id
                """, (rs, rowNum) -> new MembershipSummary(rs.getString("user_id"), rs.getString("display_name"),
                rs.getString("role"), rs.getString("status"), rs.getTimestamp("created_at").toInstant(),
                strongest(enforcementService.actions(TargetType.USER, rs.getString("user_id")).stream()
                        .filter(this::active).toList())), businessId);
    }

    private BusinessRow findBusiness(String businessId) {
        return jdbcTemplate.query("""
                select b.id, b.legal_name, b.status, b.application_id, b.created_at, b.updated_at, b.version,
                       coalesce(s.name, b.legal_name) display_name, s.status store_status,
                       (select bm.user_id from business_memberships bm where bm.business_id = b.id and bm.role='OWNER'
                         order by case bm.status when 'ACTIVE' then 0 else 1 end, bm.created_at limit 1) owner_user_id,
                       (select count(*) from business_memberships bm where bm.business_id = b.id) member_count
                from businesses b left join stores s on s.business_id = b.id where b.id = ?
                """, this::businessRow, businessId).stream().findFirst()
                .orElseThrow(() -> new EnforcementExceptions.NotFound("Business was not found."));
    }

    private String verificationState(String applicationId) {
        return jdbcTemplate.queryForObject("select status from business_applications where id = ?",
                String.class, applicationId);
    }

    private ListingSummary listingSummary(ProductBusinessListingSummaryClient.Summary value) {
        if (value == null) return new ListingSummary(0, 0, 0, 0, 0, 0);
        return new ListingSummary(value.totalCount(), value.draftCount(), value.pendingReviewCount(),
                value.activeCount(), value.pausedCount(), value.removedCount());
    }

    private AvailableAdminCapabilities capabilities(AdminAuthorizationService.AdminAccessSnapshot access) {
        return new AvailableAdminCapabilities(true, access.has(AdminPermission.BUSINESS_RESTRICT),
                access.has(AdminPermission.BUSINESS_SUSPEND), access.has(AdminPermission.BUSINESS_BAN),
                access.has(AdminPermission.BUSINESS_REINSTATE), false,
                access.has(AdminPermission.BUSINESS_RESTRICT) || access.has(AdminPermission.BUSINESS_SUSPEND)
                        || access.has(AdminPermission.BUSINESS_BAN) ? null : "Your role has read-only business access.",
                OPERATIONAL_SCOPES);
    }

    private TimelineEntry verificationTimeline(String businessId, AdminTimelineEntryResponse event) {
        return new TimelineEntry(event.eventId(), event.occurredAt(), event.eventType(), event.actorType(),
                event.actorId(), event.actorDisplay(), event.source(), "BUSINESS", businessId, null, null, Set.of(),
                event.previousState(), event.newState(), null, event.reason(), null, event.correlationId(), null,
                event.metadata());
    }

    private SearchSql searchSql(String q, String state, String enforcementState, Scope scope,
                                Instant createdFrom, Instant createdTo) {
        StringBuilder where = new StringBuilder(" where 1=1\n");
        List<Object> parameters = new ArrayList<>();
        if (q != null) {
            String like = "%" + escapedLike(q.toLowerCase(Locale.ROOT)) + "%";
            where.append(" and (b.id = ? or lower(b.legal_name) like ? escape '!' or exists (select 1 from stores qs where qs.business_id=b.id and lower(qs.name) like ? escape '!') or exists (select 1 from business_memberships qm where qm.business_id=b.id and qm.role='OWNER' and qm.user_id=?))\n");
            parameters.add(q); parameters.add(like); parameters.add(like); parameters.add(q);
        }
        if (state != null) { where.append(" and b.status = ?\n"); parameters.add(state); }
        if (createdFrom != null) { where.append(" and b.created_at >= ?\n"); parameters.add(Timestamp.from(createdFrom)); }
        if (createdTo != null) { where.append(" and b.created_at < ?\n"); parameters.add(Timestamp.from(createdTo)); }
        if (scope != null) {
            where.append(" and exists (select 1 from enforcement_actions ea join enforcement_action_scopes eas on eas.enforcement_action_id=ea.id where ea.target_type='BUSINESS' and ea.target_id=b.id and eas.scope=? and ea.revoked_at is null and ea.effective_at<=current_timestamp(6) and (ea.expires_at is null or ea.expires_at>current_timestamp(6)))\n");
            parameters.add(scope.name());
        }
        if (enforcementState != null) {
            String severity = "(select max(case ea.action_type when 'BAN' then 3 when 'SUSPEND' then 2 else 1 end) from enforcement_actions ea where ea.target_type='BUSINESS' and ea.target_id=b.id and ea.revoked_at is null and ea.effective_at<=current_timestamp(6) and (ea.expires_at is null or ea.expires_at>current_timestamp(6)))";
            if ("CLEAR".equals(enforcementState)) where.append(" and ").append(severity).append(" is null\n");
            else { where.append(" and ").append(severity).append(" = ?\n"); parameters.add(switch (enforcementState) { case "BANNED" -> 3; case "SUSPENDED" -> 2; default -> 1; }); }
        }
        return new SearchSql(where.toString(), parameters);
    }

    private AdminAuthorizationService.AdminAccessSnapshot currentAccess(AdminPermission permission) {
        User actor = authService.ensureUserEntity();
        AdminAuthorizationService.AdminAccessSnapshot access = authorizationService.accessFor(actor);
        if (!access.has(permission)) throw new BusinessApplicationForbiddenException();
        return access;
    }

    private Set<Scope> operationalScopes(Set<Scope> scopes) {
        if (scopes == null || scopes.isEmpty() || !OPERATIONAL_SCOPES.containsAll(scopes))
            throw new EnforcementExceptions.Validation("Select at least one operational business scope.");
        return Set.copyOf(scopes);
    }

    private Scope normalizedScope(Scope scope) {
        if (scope != null && !OPERATIONAL_SCOPES.contains(scope))
            throw new EnforcementExceptions.Validation("The selected business scope is not operational.");
        return scope;
    }

    private String normalizedBusinessState(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "SUSPENDED", "CLOSED").contains(normalized))
            throw new EnforcementExceptions.Validation("Business state filter is invalid.");
        return normalized;
    }

    private String normalizedEnforcementState(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CLEAR", "RESTRICTED", "SUSPENDED", "BANNED").contains(normalized))
            throw new EnforcementExceptions.Validation("Enforcement state filter is invalid.");
        return normalized;
    }

    private String normalizedSort(String value) {
        String sort = value == null || value.isBlank() ? "createdAt,desc" : value.trim();
        if (!Set.of("createdAt,desc", "createdAt,asc", "updatedAt,desc", "updatedAt,asc", "name,asc", "name,desc", "businessId,asc", "businessId,desc").contains(sort))
            throw new EnforcementExceptions.Validation("Sort is invalid.");
        return sort;
    }

    private String sortSql(String sort) {
        return switch (sort) {
            case "createdAt,asc" -> "b.created_at asc, b.id asc";
            case "updatedAt,desc" -> "b.updated_at desc, b.id desc";
            case "updatedAt,asc" -> "b.updated_at asc, b.id asc";
            case "name,asc" -> "display_name asc, b.id asc";
            case "name,desc" -> "display_name desc, b.id desc";
            case "businessId,asc" -> "b.id asc";
            case "businessId,desc" -> "b.id desc";
            default -> "b.created_at desc, b.id desc";
        };
    }

    private List<String> warnings(Result proposed, List<Result> existing) {
        List<String> warnings = new ArrayList<>();
        if (proposed.expiresAt() == null) warnings.add("This action is indefinite until explicitly revoked.");
        if (existing.stream().anyMatch(action -> action.actionType().severity() > proposed.actionType().severity()))
            warnings.add("A stronger active action already controls one or more selected scopes.");
        return List.copyOf(warnings);
    }

    private List<String> impactSummary(Set<Scope> scopes) {
        List<String> impact = new ArrayList<>();
        if (scopes.contains(Scope.BUSINESS_LISTING_CREATION)) impact.add("Will block new business listing drafts for every member.");
        if (scopes.contains(Scope.BUSINESS_LISTING_PUBLICATION)) impact.add("Will block submit, publish, and reactivation transitions; existing active listings stay active.");
        if (scopes.contains(Scope.BUSINESS_NEW_SALES)) impact.add("Will block new checkout, payment-intent, and order commitments; existing orders remain operable.");
        impact.add("Will not change memberships, user accounts, listing records, existing orders, or payouts.");
        return List.copyOf(impact);
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String value = query.trim().replaceAll("\\s+", " ");
        if (value.length() > 200) throw new EnforcementExceptions.Validation("Search query is too long.");
        return value;
    }

    private String businessId(String value) { return FixedLengthIds.requireTrimmed("Business ID", value, 26); }
    private boolean active(Result action) { return action.lifecycleState() == LifecycleState.ACTIVE; }
    private ActionType strongest(List<Result> actions) { return actions.stream().map(Result::actionType).max(Comparator.comparingInt(ActionType::severity)).orElse(null); }
    private Map<String, String> safeMetadata(Map<String, String> value) { return value == null ? Map.of() : value; }
    private String escapedLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }

    private BusinessRow businessRow(ResultSet rs, int rowNum) throws SQLException {
        return new BusinessRow(rs.getString("id"), rs.getString("legal_name"), rs.getString("display_name"),
                rs.getString("status"), rs.getString("store_status"), rs.getString("application_id"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"), rs.getString("owner_user_id"), rs.getLong("member_count"));
    }

    private record SearchSql(String where, List<Object> parameters) { }
    private record BusinessRow(String id, String legalName, String displayName, String status, String storeStatus,
                               String applicationId, Instant createdAt, Instant updatedAt, long version,
                               String ownerUserId, long memberCount) { }
}
