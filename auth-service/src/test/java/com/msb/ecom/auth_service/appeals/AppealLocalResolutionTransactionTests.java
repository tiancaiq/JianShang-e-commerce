package com.msb.ecom.auth_service.appeals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msb.ecom.auth_service.appeals.AppealContracts.Detail;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionPreview;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionPreviewRequest;
import com.msb.ecom.auth_service.appeals.AppealContracts.ResolutionRequest;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.enforcement.EnforcementExceptions;
import com.msb.ecom.auth_service.enforcement.EnforcementService;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AdminBusinessService;
import com.msb.ecom.auth_service.service.AdminUserService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import com.msb.ecom.common.testing.MySqlContainerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class AppealLocalResolutionTransactionTests {
    private static final Instant NOW = Instant.parse("2026-08-24T01:00:00Z");
    private static final String TARGET = "01ARZ3NDEKTSV4RRFFQ69G7AAA";
    private static final String REVIEWER = "01ARZ3NDEKTSV4RRFFQ69G7AAB";
    private static final String EXECUTOR = "01ARZ3NDEKTSV4RRFFQ69G7AAC";
    private static final String ACTION = "01ARZ3NDEKTSV4RRFFQ69G7AAD";
    private static final String APPEAL = "01ARZ3NDEKTSV4RRFFQ69G7AAE";

    @Container
    static MySQLContainer<?> mysql = MySqlContainerFactory.create("identity");

    static JdbcTemplate jdbc;
    static AppealRepository repository;
    static DataSourceTransactionManager transactionManager;
    static AppealResolutionCommandService resolutionCommands;

    AuthService authService;
    AdminAuthorizationService authorization;
    AdminUserService adminUsers;
    EnforcementService enforcements;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new AppealRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        transactionManager = new DataSourceTransactionManager(dataSource);
        resolutionCommands = transactional(new AppealResolutionCommandService(jdbc));
    }

    @BeforeEach
    void setUp() {
        jdbc.update("delete from appeal_resolution_commands where appeal_id=?", APPEAL);
        jdbc.update("delete from appeal_resolution_previews where appeal_id=?", APPEAL);
        jdbc.update("delete from appeal_events where appeal_id=?", APPEAL);
        jdbc.update("delete from appeal_replacement_scopes where appeal_id=?", APPEAL);
        jdbc.update("delete from appeals where id=?", APPEAL);
        jdbc.update("delete from enforcement_events where enforcement_action_id=?", ACTION);
        jdbc.update("delete from enforcement_action_scopes where enforcement_action_id=?", ACTION);
        jdbc.update("delete from enforcement_actions where id=?", ACTION);
        jdbc.update("delete from users where id in (?, ?, ?)", TARGET, REVIEWER, EXECUTOR);

        insertUser(TARGET, "Affected user", "appeal-target", 2);
        insertUser(REVIEWER, "Review administrator", "appeal-reviewer", 0);
        insertUser(EXECUTOR, "Resolution administrator", "appeal-executor", 0);
        insertAction();

        authService = mock(AuthService.class);
        authorization = mock(AdminAuthorizationService.class);
        adminUsers = mock(AdminUserService.class);
        enforcements = mock(EnforcementService.class);
        User executor = User.create(EXECUTOR, "appeal-executor-sub", "executor@example.test",
                true, "Resolution administrator", "appeal-executor-runtime");
        when(authService.ensureUserEntity()).thenReturn(executor);
        when(enforcements.lockState(eq(TargetType.USER), eq(TARGET), any())).thenReturn(
                new EnforcementService.StatePin(2L, "stable-owner-state", List.of(
                        new EffectiveRestriction(Scope.USER_SELLING, ActionType.SUSPEND, ACTION))));
        when(enforcements.evaluate(eq(TargetType.USER), eq(TARGET), any())).thenReturn(List.of(
                new EffectiveRestriction(Scope.USER_SELLING, ActionType.SUSPEND, ACTION)));
    }

    @Test
    void localModifyRollsBackTheOriginalRevokeWhenReplacementCreationFails() {
        insertRecommended("MODIFY_RECOMMENDED", "MODIFY_RECOMMENDED", true);
        when(authorization.accessFor(any())).thenReturn(access(
                "admin.appeal.resolve", "admin.user.reinstate", "admin.user.restrict"));
        when(adminUsers.revokePreview(eq(TARGET), eq(ACTION), any())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        result(ACTION, ActionType.SUSPEND, 2, true, List.of()),
                        List.of(), List.of(), List.of(), false, true));
        EffectiveRestriction adjusted = new EffectiveRestriction(
                Scope.USER_SELLING, ActionType.RESTRICT, "01ARZ3NDEKTSV4RRFFQ69G7AAF");
        when(adminUsers.createAppealReplacementPreview(eq(TARGET), eq(ACTION), any(), isNull())).thenReturn(
                new com.msb.ecom.auth_service.dto.AdminUserContracts.EnforcementPreview(
                        result(null, ActionType.RESTRICT, 0, true, List.of(adjusted)),
                        List.of(), List.of(adjusted), List.of(), false, true));
        when(adminUsers.revokeConfirmed(eq(TARGET), eq(ACTION), any())).thenAnswer(invocation -> {
            jdbc.update("update enforcement_actions set revoked_at=?, version=version+1 where id=?",
                    Timestamp.from(NOW), ACTION);
            return result(ACTION, ActionType.SUSPEND, 2, false, List.of());
        });
        when(adminUsers.createCaseConfirmed(eq(TARGET), any(), isNull())).thenThrow(
                new EnforcementExceptions.Validation("Synthetic replacement failure."));
        AppealService service = service();
        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "rollback-preview");

        assertThatThrownBy(() -> service.resolveTransaction(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, preview.previewToken(),
                        "rollback-resolution-key", true), "rollback-resolve"))
                .isInstanceOfSatisfying(AppealResolutionAttemptException.class,
                        failure -> assertThat(failure.original())
                                .isInstanceOfSatisfying(AppealException.class,
                                        appeal -> assertThat(appeal.code())
                                                .isEqualTo("APPEAL_RESOLUTION_INVALID")));

        assertThat(jdbc.queryForMap("select revoked_at, version from enforcement_actions where id=?", ACTION))
                .containsEntry("revoked_at", null)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForMap("select status, version from appeals where id=?", APPEAL))
                .containsEntry("status", "MODIFY_RECOMMENDED")
                .containsEntry("version", 5L);
        assertThat(jdbc.queryForObject(
                "select count(*) from appeal_events where appeal_id=? and event_type like 'APPEAL_ENFORCEMENT_%'",
                Integer.class, APPEAL)).isZero();
    }

    @Test
    void differentReviewerAndResolveOnlyExecutorArePersistedInTheFinalAudit() {
        insertRecommended("UPHOLD_RECOMMENDED", "UPHOLD_RECOMMENDED", false);
        when(authorization.accessFor(any())).thenReturn(access("admin.appeal.resolve"));
        AppealService service = service();
        ResolutionPreview preview = service.previewResolution(APPEAL,
                new ResolutionPreviewRequest(5L, 1L, 2L), "audit-preview");

        Detail detail = service.resolveTransaction(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, preview.previewToken(),
                        "audit-resolution-key", true), "audit-resolve");

        assertThat(detail.status()).isEqualTo(AppealContracts.Status.UPHELD);
        assertThat(jdbc.queryForMap("""
                select assigned_admin_id, resolved_by_admin_id, resolved_by_admin_display_name
                from appeals where id=?
                """, APPEAL))
                .containsEntry("assigned_admin_id", REVIEWER)
                .containsEntry("resolved_by_admin_id", EXECUTOR)
                .containsEntry("resolved_by_admin_display_name", "Resolution administrator");
        assertThat(jdbc.queryForMap("""
                select actor_id, actor_display_name, actor_type, event_type
                from appeal_events where appeal_id=? and event_type='APPEAL_UPHELD'
                """, APPEAL))
                .containsEntry("actor_id", EXECUTOR)
                .containsEntry("actor_display_name", "Resolution administrator")
                .containsEntry("actor_type", "PLATFORM_ADMIN")
                .containsEntry("event_type", "APPEAL_UPHELD");
    }

    @Test
    void unauthorizedResolutionDenialDoesNotAppendAFalsePlatformAdminFailureEvent() {
        insertRecommended("UPHOLD_RECOMMENDED", "UPHOLD_RECOMMENDED", false);
        when(authorization.accessFor(any())).thenReturn(access());
        AppealResolutionFailureAuditService failureAudit = new AppealResolutionFailureAuditService(
                repository, authService, new UlidGenerator(), Clock.fixed(NOW, ZoneOffset.UTC));
        AppealResolutionService resolution = new AppealResolutionService(service(), failureAudit);

        assertThatThrownBy(() -> resolution.resolve(APPEAL,
                new ResolutionRequest(5L, 1L, 2L, APPEAL,
                        "unauthorized-resolution-key", true), "unauthorized-resolve"))
                .isInstanceOfSatisfying(AppealException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("APPEAL_RESOLUTION_PERMISSION_REQUIRED"));

        assertThat(jdbc.queryForObject(
                "select count(*) from appeal_events where appeal_id=?",
                Integer.class, APPEAL)).isZero();
    }

    private AppealService service() {
        AppealService target = new AppealService(repository, mock(ListingAppealContextClient.class),
                authService, authorization, adminUsers, mock(AdminBusinessService.class), enforcements,
                resolutionCommands, new UlidGenerator(), new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return transactional(target);
    }

    private void insertRecommended(String status, String outcome, boolean replacement) {
        jdbc.update("""
                insert into appeals (
                    id, enforcement_action_id, target_type, target_id, safe_target_label,
                    appellant_type, appellant_user_id, status, reason_code, explanation,
                    safe_evidence_references, submitted_at, assigned_admin_id, review_started_at,
                    reviewed_at, review_outcome, review_reason_code, review_reason,
                    replacement_action_type, replacement_expires_at, replacement_reason_code,
                    replacement_reason, replacement_expected_target_version, original_enforcement_version,
                    correlation_id, version, created_at, updated_at
                ) values (?, ?, 'USER', ?, 'Affected user', 'USER', ?, ?, 'ACTION_TOO_SEVERE',
                    'Please review', cast('[]' as json), ?, ?, ?, ?, ?, 'POLICY_REVIEW',
                    'The reviewer recorded this recommendation.', ?, ?, ?, ?, ?, 1,
                    'appeal-transaction-test', 5, ?, ?)
                """, APPEAL, ACTION, TARGET, TARGET, status, Timestamp.from(NOW.minusSeconds(120)),
                REVIEWER, Timestamp.from(NOW.minusSeconds(90)), Timestamp.from(NOW.minusSeconds(60)), outcome,
                replacement ? "RESTRICT" : null, replacement ? Timestamp.from(NOW.plusSeconds(3600)) : null,
                replacement ? "APPEAL_ADJUSTED" : null, replacement ? "Adjusted enforcement" : null,
                replacement ? 2L : null, Timestamp.from(NOW.minusSeconds(120)), Timestamp.from(NOW));
        if (replacement) {
            jdbc.update("insert into appeal_replacement_scopes (appeal_id, scope) values (?, ?)",
                    APPEAL, Scope.USER_SELLING.name());
        }
    }

    private void insertAction() {
        jdbc.update("""
                insert into enforcement_actions (
                    id, target_type, target_id, action_type, version, effective_at, expires_at,
                    reason_code, reason, case_id, parent_enforcement_action_id,
                    target_version_at_decision, source, created_by_actor_id,
                    created_by_actor_display_name, created_at, revoked_at, correlation_id, request_id,
                    idempotency_key, command_fingerprint, safe_metadata
                ) values (?, 'USER', ?, 'SUSPEND', 1, ?, null, 'POLICY',
                    'Original enforcement', null, null, 2, 'HUMAN_ADMIN', ?,
                    'Review administrator', ?, null, 'appeal-action', ?,
                    'appeal-action-key', ?, cast('{}' as json))
                """, ACTION, TARGET, Timestamp.from(NOW.minusSeconds(300)), REVIEWER,
                Timestamp.from(NOW.minusSeconds(300)), "01ARZ3NDEKTSV4RRFFQ69G7AAG", "1".repeat(64));
        jdbc.update("insert into enforcement_action_scopes (enforcement_action_id, scope) values (?, ?)",
                ACTION, Scope.USER_SELLING.name());
    }

    private static void insertUser(String id, String name, String handle, long version) {
        jdbc.update("""
                insert into users (
                    id, keycloak_sub, email_verified, display_name, public_handle,
                    phone_verified, status, account_type, version, created_at, updated_at
                ) values (?, ?, false, ?, ?, false, 'ACTIVE', 'HUMAN', ?, ?, ?)
                """, id, "sub-" + id, name, handle, version,
                Timestamp.from(NOW.minusSeconds(600)), Timestamp.from(NOW));
    }

    private Result result(String actionId, ActionType actionType, long version, boolean dryRun,
                          List<EffectiveRestriction> restrictions) {
        return new Result(actionId, TargetType.USER, TARGET, actionType, Set.of(Scope.USER_SELLING),
                actionId != null && version > 1 ? LifecycleState.REVOKED : LifecycleState.ACTIVE,
                NOW.minusSeconds(300), actionType == ActionType.RESTRICT ? NOW.plusSeconds(3600) : null,
                version, NOW.minusSeconds(300), version > 1 ? NOW : null,
                "APPEAL_RESOLUTION", "Appeal resolution", restrictions, "appeal-transaction-test", dryRun);
    }

    private AdminAuthorizationService.AdminAccessSnapshot access(String... permissions) {
        return new AdminAuthorizationService.AdminAccessSnapshot(
                List.of("APPEAL_RESOLVER"), List.of(permissions));
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
