package com.msb.ecom.auth_service.enforcement;

import com.msb.ecom.auth_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.Source;
import com.msb.ecom.auth_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.auth_service.model.User;
import com.msb.ecom.auth_service.repository.UserRepository;
import com.msb.ecom.auth_service.security.AdminPermission;
import com.msb.ecom.auth_service.service.AdminAuthorizationService;
import com.msb.ecom.auth_service.service.AuthService;
import com.msb.ecom.auth_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnforcementServiceTests {
    private static final String ACTOR = "01ARZ3NDEKTSV4RRFFQ69G5FCA";
    private static final String TARGET = "01ARZ3NDEKTSV4RRFFQ69G5FCB";
    private static final String ACTION = "01ARZ3NDEKTSV4RRFFQ69G5FCC";
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void dryRunChecksAuthenticatedPermissionAndTargetVersionWithoutPersistence() {
        EnforcementRepository repository = mock(EnforcementRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuthService auth = mock(AuthService.class);
        AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
        User actor = User.create(ACTOR, "subject", "admin@example.test", true, "Safety Admin", "safety-admin");
        User target = User.create(TARGET, "target-subject", "target@example.test", true, "Target", "target-user");
        when(auth.ensureUserEntity()).thenReturn(actor);
        when(users.lockById(TARGET)).thenReturn(Optional.of(target));
        when(repository.activeActions(TargetType.USER, TARGET, java.time.Instant.now())).thenReturn(List.of());
        when(repository.activeActions(org.mockito.ArgumentMatchers.eq(TargetType.USER),
                org.mockito.ArgumentMatchers.eq(TARGET), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        EnforcementService service = new EnforcementService(repository, users, auth, authorization, mock(UlidGenerator.class));

        var result = service.create(new CreateCommand(TargetType.USER, TARGET, ActionType.RESTRICT,
                Set.of(Scope.USER_SELLING), "POLICY_ABUSE", "Human reason", null, null, null,
                0L, null, Map.of(), true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.enforcementActionId()).isNull();
        verify(authorization).requirePermission(actor, AdminPermission.USER_RESTRICT);
        verify(repository, never()).reserveIdempotency(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertAction(org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insertEvent(org.mockito.ArgumentMatchers.any());
    }

    @ParameterizedTest
    @MethodSource("creationPermissions")
    void creationUsesTheFineGrainedPermissionForTargetAndAction(
            TargetType targetType, ActionType actionType, Scope scope, AdminPermission expectedPermission) {
        Fixture fixture = fixture();
        if (targetType == TargetType.USER) {
            when(fixture.users.lockById(TARGET)).thenReturn(Optional.of(fixture.target));
        } else {
            when(fixture.repository.lockBusiness(TARGET))
                    .thenReturn(Optional.of(new EnforcementRepository.TargetSnapshot(TARGET, 0)));
        }
        when(fixture.repository.activeActions(eq(targetType), eq(TARGET), any())).thenReturn(List.of());

        fixture.service.create(command(targetType, actionType, scope, true, null, 0L));

        verify(fixture.authorization).requirePermission(fixture.actor, expectedPermission);
    }

    @Test
    void deniedPermissionStopsBeforeTargetLookupAndPersistence() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("forbidden")).when(fixture.authorization)
                .requirePermission(fixture.actor, AdminPermission.USER_BAN);

        assertThatThrownBy(() -> fixture.service.create(
                command(TargetType.USER, ActionType.BAN, Scope.USER_LOGIN, false, "denied-key", 0L)))
                .isInstanceOf(IllegalStateException.class).hasMessage("forbidden");

        verify(fixture.users, never()).lockById(any());
        verify(fixture.repository, never()).reserveIdempotency(any(), any(), any(), any());
    }

    @Test
    void authenticatedPrincipalSuppliesActorAndClientCannotSupplyOne() {
        Fixture fixture = fixture();
        when(fixture.users.lockById(TARGET)).thenReturn(Optional.of(fixture.target));
        when(fixture.repository.activeActions(eq(TargetType.USER), eq(TARGET), any())).thenReturn(List.of());
        when(fixture.repository.findIdempotency(eq("CREATE"), eq("actor-key"), anyBoolean()))
                .thenReturn(Optional.empty());
        when(fixture.repository.reserveIdempotency(eq("CREATE"), eq("actor-key"), any(), any())).thenReturn(true);
        when(fixture.ids.next()).thenReturn(ACTION, "01ARZ3NDEKTSV4RRFFQ69G5FCD",
                "01ARZ3NDEKTSV4RRFFQ69G5FCE", "01ARZ3NDEKTSV4RRFFQ69G5FCF");

        fixture.service.create(command(TargetType.USER, ActionType.RESTRICT,
                Scope.USER_SELLING, false, "actor-key", 0L));

        ArgumentCaptor<EnforcementRepository.Action> action = ArgumentCaptor.forClass(EnforcementRepository.Action.class);
        ArgumentCaptor<EnforcementRepository.Event> event = ArgumentCaptor.forClass(EnforcementRepository.Event.class);
        verify(fixture.repository).insertAction(action.capture());
        verify(fixture.repository).insertEvent(event.capture());
        assertThat(action.getValue().createdByActorId()).isEqualTo(ACTOR);
        assertThat(action.getValue().createdByActorDisplayName()).isEqualTo("Safety Admin");
        assertThat(event.getValue().actorId()).isEqualTo(ACTOR);
        assertThat(event.getValue().source()).isEqualTo(Source.HUMAN_ADMIN);
    }

    @Test
    void exactReplayReturnsOriginalBeforeTargetLock() {
        Fixture fixture = fixture();
        CreateCommand command = command(TargetType.USER, ActionType.RESTRICT,
                Scope.USER_SELLING, false, "replay-key", 0L);
        var normalized = new EnforcementPolicy().normalize(command, NOW.plusSeconds(3600));
        String fingerprint = new EnforcementPolicy().fingerprint(normalized);
        EnforcementRepository.Action stored = action(ActionType.RESTRICT, Set.of(Scope.USER_SELLING), null, 0);
        when(fixture.repository.findIdempotency("CREATE", "replay-key", false))
                .thenReturn(Optional.of(new EnforcementRepository.Idempotency(fingerprint, ACTION)));
        when(fixture.repository.findAction(ACTION, false)).thenReturn(Optional.of(stored));
        when(fixture.repository.activeActions(eq(TargetType.USER), eq(TARGET), any())).thenReturn(List.of(stored));

        var result = fixture.service.create(command);

        assertThat(result.enforcementActionId()).isEqualTo(ACTION);
        verify(fixture.users, never()).lockById(any());
        verify(fixture.repository, never()).insertAction(any());
    }

    @Test
    void changedPayloadWithReusedKeyConflicts() {
        Fixture fixture = fixture();
        CreateCommand original = command(TargetType.USER, ActionType.RESTRICT,
                Scope.USER_SELLING, false, "changed-key", 0L);
        var normalized = new EnforcementPolicy().normalize(original, NOW.plusSeconds(3600));
        when(fixture.repository.findIdempotency("CREATE", "changed-key", false)).thenReturn(Optional.of(
                new EnforcementRepository.Idempotency(new EnforcementPolicy().fingerprint(normalized), ACTION)));
        CreateCommand changed = new CreateCommand(TargetType.USER, TARGET, ActionType.RESTRICT,
                Set.of(Scope.USER_BUYING), "POLICY_ABUSE", "Changed reason", null, null, null,
                0L, "changed-key", Map.of(), false);

        assertThatThrownBy(() -> fixture.service.create(changed))
                .isInstanceOf(EnforcementExceptions.Conflict.class)
                .hasMessageContaining("different command");
        verify(fixture.repository, never()).insertAction(any());
    }

    @Test
    void staleTargetVersionAndExactActiveDuplicateDoNotConsumeKey() {
        Fixture stale = fixture();
        when(stale.users.lockById(TARGET)).thenReturn(Optional.of(stale.target));
        assertThatThrownBy(() -> stale.service.create(command(TargetType.USER, ActionType.SUSPEND,
                Scope.USER_LOGIN, false, "stale-key", 1L)))
                .isInstanceOf(EnforcementExceptions.Conflict.class).hasMessageContaining("target version");
        verify(stale.repository, never()).reserveIdempotency(any(), any(), any(), any());

        Fixture duplicate = fixture();
        when(duplicate.users.lockById(TARGET)).thenReturn(Optional.of(duplicate.target));
        when(duplicate.repository.activeActions(eq(TargetType.USER), eq(TARGET), any()))
                .thenReturn(List.of(action(ActionType.SUSPEND, Set.of(Scope.USER_LOGIN), null, 0)));
        assertThatThrownBy(() -> duplicate.service.create(command(TargetType.USER, ActionType.SUSPEND,
                Scope.USER_LOGIN, false, "duplicate-key", 0L)))
                .isInstanceOf(EnforcementExceptions.Conflict.class).hasMessageContaining("equivalent active");
        verify(duplicate.repository, never()).reserveIdempotency(any(), any(), any(), any());
    }

    @Test
    void dryRunRevocationRequiresReinstatePermissionAndWritesNothing() {
        Fixture fixture = fixture();
        EnforcementRepository.Action action = action(ActionType.BAN, Set.of(Scope.USER_LOGIN), null, 0);
        when(fixture.repository.findAction(ACTION, false)).thenReturn(Optional.of(action));
        when(fixture.repository.findAction(ACTION, true)).thenReturn(Optional.of(action));
        when(fixture.repository.activeActions(eq(TargetType.USER), eq(TARGET), any())).thenReturn(List.of(action));

        var result = fixture.service.revoke(new RevokeCommand(
                ACTION, 0L, "APPEAL_ACCEPTED", "Reviewed and restored", null, Map.of(), true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.lifecycleState()).isEqualTo(EnforcementContracts.LifecycleState.REVOKED);
        verify(fixture.authorization).requirePermission(fixture.actor, AdminPermission.USER_REINSTATE);
        verify(fixture.repository, never()).reserveIdempotency(any(), any(), any(), any());
        verify(fixture.repository, never()).revoke(any(), any(), any(), any(), any());
        verify(fixture.repository, never()).insertEvent(any());
    }

    private static Stream<Arguments> creationPermissions() {
        return Stream.of(
                Arguments.of(TargetType.USER, ActionType.RESTRICT, Scope.USER_SELLING, AdminPermission.USER_RESTRICT),
                Arguments.of(TargetType.USER, ActionType.SUSPEND, Scope.USER_LOGIN, AdminPermission.USER_SUSPEND),
                Arguments.of(TargetType.USER, ActionType.BAN, Scope.USER_LOGIN, AdminPermission.USER_BAN),
                Arguments.of(TargetType.BUSINESS, ActionType.RESTRICT, Scope.BUSINESS_NEW_SALES,
                        AdminPermission.BUSINESS_RESTRICT),
                Arguments.of(TargetType.BUSINESS, ActionType.SUSPEND, Scope.BUSINESS_LISTING_PUBLICATION,
                        AdminPermission.BUSINESS_SUSPEND),
                Arguments.of(TargetType.BUSINESS, ActionType.BAN, Scope.BUSINESS_LISTING_CREATION,
                        AdminPermission.BUSINESS_BAN));
    }

    private static CreateCommand command(TargetType targetType, ActionType actionType, Scope scope,
                                         boolean dryRun, String key, long version) {
        return new CreateCommand(targetType, TARGET, actionType, Set.of(scope), "POLICY_ABUSE", "Human reason",
                null, NOW, null, version, key, Map.of(), dryRun);
    }

    private static EnforcementRepository.Action action(ActionType type, Set<Scope> scopes,
                                                       Instant revokedAt, long version) {
        return new EnforcementRepository.Action(ACTION, TargetType.USER, TARGET, type, scopes, version,
                NOW.minusSeconds(60), null, "POLICY_ABUSE", "Human reason", null, 0,
                Source.HUMAN_ADMIN, ACTOR, "Safety Admin", NOW.minusSeconds(60), revokedAt,
                "correlation", "01ARZ3NDEKTSV4RRFFQ69G5FCD", "key", "a".repeat(64),
                null, null, Map.of());
    }

    private static Fixture fixture() {
        EnforcementRepository repository = mock(EnforcementRepository.class);
        UserRepository users = mock(UserRepository.class);
        AuthService auth = mock(AuthService.class);
        AdminAuthorizationService authorization = mock(AdminAuthorizationService.class);
        UlidGenerator ids = mock(UlidGenerator.class);
        User actor = User.create(ACTOR, "subject", "admin@example.test", true, "Safety Admin", "safety-admin");
        User target = User.create(TARGET, "target-subject", "target@example.test", true, "Target", "target-user");
        when(auth.ensureUserEntity()).thenReturn(actor);
        return new Fixture(repository, users, authorization, ids, actor, target,
                new EnforcementService(repository, users, auth, authorization, ids));
    }

    private record Fixture(EnforcementRepository repository, UserRepository users,
                           AdminAuthorizationService authorization, UlidGenerator ids, User actor,
                           User target, EnforcementService service) { }
}
