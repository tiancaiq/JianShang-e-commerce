package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.common.web.security.CurrentActor;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.RevokeCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.security.AdminPermission;
import com.msb.ecom.product_service.service.AuthServiceClient;
import com.msb.ecom.product_service.service.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnforcementServiceTests {
    private static final String TARGET = "01ARZ3NDEKTSV4RRFFQ69G5FDA";
    private static final String ACTION = "01ARZ3NDEKTSV4RRFFQ69G5FDC";
    private static final String ADMIN = "01ARZ3NDEKTSV4RRFFQ69G5FDB";
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void dryRunUsesExistingAdminSessionAndDoesNotPersistOrResolveLabels() {
        EnforcementRepository repository = mock(EnforcementRepository.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        AuthServiceClient auth = mock(AuthServiceClient.class);
        when(actors.currentActor()).thenReturn(new CurrentActor(
                "subject", "token", "admin@example.test", "Safety Admin", true));
        when(auth.requirePlatformAdmin("token")).thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                ADMIN, "PLATFORM_ADMIN", List.of("TRUST_AND_SAFETY_ADMIN"),
                List.of(AdminPermission.LISTING_SUSPEND.id()), "ACTIVE"));
        when(repository.lockListing(TARGET)).thenReturn(Optional.of(activeTarget()));
        when(repository.active(org.mockito.ArgumentMatchers.eq(TARGET), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        EnforcementService service = new EnforcementService(repository, actors, auth, mock(UlidGenerator.class));

        var result = service.create(new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "POLICY_ABUSE", "Human reason", null,
                null, null, 3L, null, Map.of(), true));

        assertThat(result.dryRun()).isTrue();
        verify(repository, never()).reserve(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(repository, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(auth, never()).lookupAdminIdentityLabels(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingListingSuspendPermissionIsRejectedBeforeTargetLookup() {
        Fixture fixture = fixture(List.of(AdminPermission.AUDIT_READ.id()));

        assertThatThrownBy(() -> fixture.service.create(create("denied", 3L, false)))
                .isInstanceOf(ListingAuthorizationException.class);

        verify(fixture.repository, never()).lockListing(any());
        verify(fixture.repository, never()).insert(any());
    }

    @Test
    void administrativelyRemovedListingCannotReceiveTemporaryEnforcement() {
        Fixture fixture = fixture(List.of(AdminPermission.LISTING_SUSPEND.id()));
        when(fixture.repository.lockListing(TARGET)).thenReturn(Optional.of(
                new EnforcementRepository.Target(TARGET, 3, "REMOVED_BY_ADMIN", "REJECTED")));

        assertThatThrownBy(() -> fixture.service.create(create("removed", 3L, false)))
                .isInstanceOf(EnforcementExceptions.Validation.class)
                .hasMessageContaining("Administratively removed");
        verify(fixture.repository, never()).reserve(any(), any(), any(), any());
    }

    @Test
    void staleListingVersionAndDuplicateDoNotConsumeIdempotencyKey() {
        Fixture stale = fixture(List.of(AdminPermission.LISTING_SUSPEND.id()));
        when(stale.repository.lockListing(TARGET)).thenReturn(Optional.of(activeTarget()));
        assertThatThrownBy(() -> stale.service.create(create("stale", 2L, false)))
                .isInstanceOf(EnforcementExceptions.Conflict.class).hasMessageContaining("listing version");
        verify(stale.repository, never()).reserve(any(), any(), any(), any());

        Fixture duplicate = fixture(List.of(AdminPermission.LISTING_SUSPEND.id()));
        when(duplicate.repository.lockListing(TARGET))
                .thenReturn(Optional.of(activeTarget()));
        when(duplicate.repository.active(eq(TARGET), any())).thenReturn(List.of(action(null, 0)));
        assertThatThrownBy(() -> duplicate.service.create(create("duplicate", 3L, false)))
                .isInstanceOf(EnforcementExceptions.Conflict.class).hasMessageContaining("equivalent active");
        verify(duplicate.repository, never()).reserve(any(), any(), any(), any());
    }

    @Test
    void exactReplayReturnsOriginalAndChangedPayloadConflicts() {
        Fixture replay = fixture(List.of(AdminPermission.LISTING_SUSPEND.id()));
        CreateCommand original = create("replay-key", 3L, false);
        EnforcementPolicy policy = new EnforcementPolicy();
        String fingerprint = policy.fingerprint(policy.normalize(original, EFFECTIVE_AT.plusSeconds(3600)));
        when(replay.repository.idempotency("CREATE", "replay-key", false))
                .thenReturn(Optional.of(new EnforcementRepository.Idempotency(fingerprint, ACTION)));
        when(replay.repository.action(ACTION, false)).thenReturn(Optional.of(action(null, 0)));
        when(replay.repository.active(eq(TARGET), any())).thenReturn(List.of(action(null, 0)));
        assertThat(replay.service.create(original).enforcementActionId()).isEqualTo(ACTION);
        verify(replay.repository, never()).lockListing(any());

        Fixture changed = fixture(List.of(AdminPermission.LISTING_SUSPEND.id()));
        when(changed.repository.idempotency("CREATE", "replay-key", false))
                .thenReturn(Optional.of(new EnforcementRepository.Idempotency(fingerprint, ACTION)));
        CreateCommand changedReason = new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "POLICY_ABUSE", "Changed reason", null,
                EFFECTIVE_AT, null, 3L, "replay-key", Map.of(), false);
        assertThatThrownBy(() -> changed.service.create(changedReason))
                .isInstanceOf(EnforcementExceptions.Conflict.class).hasMessageContaining("different command");
    }

    @Test
    void createPersistsTrustedActorAndHumanSource() {
        Fixture fixture = fixture(List.of(AdminPermission.LISTING_SUSPEND.id()));
        when(fixture.repository.lockListing(TARGET)).thenReturn(Optional.of(activeTarget()));
        when(fixture.repository.active(eq(TARGET), any())).thenReturn(List.of());
        when(fixture.repository.idempotency(eq("CREATE"), eq("actor-key"), anyBoolean()))
                .thenReturn(Optional.empty());
        when(fixture.repository.reserve(eq("CREATE"), eq("actor-key"), any(), any())).thenReturn(true);
        when(fixture.ids.next()).thenReturn(ACTION, "01ARZ3NDEKTSV4RRFFQ69G5FDD",
                "01ARZ3NDEKTSV4RRFFQ69G5FDE", "01ARZ3NDEKTSV4RRFFQ69G5FDF");

        fixture.service.create(create("actor-key", 3L, false));

        ArgumentCaptor<EnforcementRepository.Action> action = ArgumentCaptor.forClass(EnforcementRepository.Action.class);
        verify(fixture.repository).insert(action.capture());
        assertThat(action.getValue().actorId()).isEqualTo(ADMIN);
        assertThat(action.getValue().actorDisplay()).isEqualTo("Platform administrator");
    }

    @Test
    void dryRunRevocationRequiresReinstateAndDoesNotPersist() {
        Fixture fixture = fixture(List.of(AdminPermission.LISTING_REINSTATE.id()));
        EnforcementRepository.Action action = action(null, 0);
        when(fixture.repository.action(ACTION, true)).thenReturn(Optional.of(action));
        when(fixture.repository.active(eq(TARGET), any())).thenReturn(List.of(action));

        var result = fixture.service.revoke(new RevokeCommand(
                ACTION, 0L, "RESTORED", "Reviewed and restored", null, Map.of(), true));

        assertThat(result.dryRun()).isTrue();
        verify(fixture.repository, never()).reserve(any(), any(), any(), any());
        verify(fixture.repository, never()).revoke(any(), any(), any(), any(), any());
        verify(fixture.repository, never()).event(any());
    }

    @Test
    void legacySuperAdminAuthorizationIncludesNewListingPermissions() {
        var authorization = new AuthServiceClient.PlatformAdminAuthorization(ADMIN, "PLATFORM_ADMIN");
        assertThat(authorization.hasPermission(AdminPermission.LISTING_SUSPEND)).isTrue();
        assertThat(authorization.hasPermission(AdminPermission.LISTING_REINSTATE)).isTrue();
    }

    private static CreateCommand create(String key, long version, boolean dryRun) {
        return new CreateCommand(TargetType.LISTING, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), "POLICY_ABUSE", "Human reason", null,
                EFFECTIVE_AT, null, version, dryRun ? null : key, Map.of(), dryRun);
    }

    private static EnforcementRepository.Action action(Instant revokedAt, long version) {
        return new EnforcementRepository.Action(ACTION, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY), version, EFFECTIVE_AT, null, "POLICY_ABUSE",
                "Human reason", null, 3, ADMIN, "Platform administrator", EFFECTIVE_AT, revokedAt,
                "correlation", "01ARZ3NDEKTSV4RRFFQ69G5FDD", "key", "a".repeat(64), Map.of());
    }

    private static EnforcementRepository.Target activeTarget() {
        return new EnforcementRepository.Target(TARGET, 3, "ACTIVE", "APPROVED");
    }

    private static Fixture fixture(List<String> permissions) {
        EnforcementRepository repository = mock(EnforcementRepository.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        AuthServiceClient auth = mock(AuthServiceClient.class);
        UlidGenerator ids = mock(UlidGenerator.class);
        when(actors.currentActor()).thenReturn(new CurrentActor(
                "subject", "token", "admin@example.test", "Safety Admin", true));
        when(auth.requirePlatformAdmin("token")).thenReturn(new AuthServiceClient.PlatformAdminAuthorization(
                ADMIN, "PLATFORM_ADMIN", List.of("TRUST_AND_SAFETY_ADMIN"), permissions, "ACTIVE"));
        return new Fixture(repository, ids, new EnforcementService(repository, actors, auth, ids));
    }

    private record Fixture(EnforcementRepository repository, UlidGenerator ids, EnforcementService service) { }
}
