package com.msb.ecom.product_service.enforcement;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.EffectiveRestriction;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.LifecycleState;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Result;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.TargetType;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Executor;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Outcome;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Replacement;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Request;
import com.msb.ecom.product_service.security.AdminPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingAppealResolutionServiceTests {
    private static final Instant PREVIEWED_AT = Instant.parse("2026-08-26T12:00:00Z");
    private static final Instant EXPIRES_AT = PREVIEWED_AT.plusSeconds(30);
    private static final String TARGET = "01ARZ3NDEKTSV4RRFFQ69G93A0";
    private static final String ACTION = "01ARZ3NDEKTSV4RRFFQ69G93B0";
    private static final String APPEAL = "01ARZ3NDEKTSV4RRFFQ69G93C0";
    private static final String ADMIN = "01ARZ3NDEKTSV4RRFFQ69G93D0";

    @Test
    void firstModifiedExecutionRejectsReplacementThatExpiredAfterPreviewBeforeAnyMutation() {
        EnforcementRepository repository = mock(EnforcementRepository.class);
        EnforcementService enforcement = mock(EnforcementService.class);
        MutableClock clock = new MutableClock(PREVIEWED_AT);
        ListingAppealResolutionService service = new ListingAppealResolutionService(repository, enforcement, clock);
        EnforcementRepository.Action action = action();
        EnforcementRepository.Target target = new EnforcementRepository.Target(TARGET, 3, "ACTIVE", "APPROVED");
        when(repository.appealResolution(any(), anyBoolean())).thenReturn(Optional.empty());
        when(repository.action(ACTION, false)).thenReturn(Optional.of(action));
        when(repository.lockListing(TARGET)).thenReturn(Optional.of(target));
        when(repository.all(TARGET, true)).thenReturn(List.of(action));
        when(enforcement.revokeTrusted(any(), any())).thenReturn(result(
                ACTION, ActionType.SUSPEND, LifecycleState.REVOKED, List.of()));
        when(enforcement.previewReplacementAfterRevocation(any(), eq(ACTION), any())).thenReturn(result(
                null, ActionType.RESTRICT, LifecycleState.ACTIVE,
                List.of(new EffectiveRestriction(
                        Scope.LISTING_PUBLIC_VISIBILITY, ActionType.RESTRICT, null))));

        var preview = service.preview(APPEAL, request(false, null, false));
        clock.advanceTo(EXPIRES_AT);
        clearInvocations(repository, enforcement);

        assertThatThrownBy(() -> service.execute(APPEAL,
                request(true, preview.confirmationToken(), false)))
                .isInstanceOf(EnforcementExceptions.Validation.class)
                .hasMessageContaining("expired at execution time");

        verify(repository, never()).reserveAppealResolution(eq("expiry-command"), any(), any(), any(), any(),
                anyLong(), anyLong(), any(), any(), any());
        verify(repository, never()).completeAppealResolution(any(), any(), any());
        verifyNoInteractions(enforcement);
    }

    private static Request request(boolean confirmed, String token, boolean recoveryOnly) {
        Replacement replacement = new Replacement(ActionType.RESTRICT,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), Instant.EPOCH, EXPIRES_AT,
                "APPEAL_ADJUSTED", "Adjusted listing restriction");
        Executor executor = new Executor(ADMIN, "Appeal administrator", Set.of(
                "admin.appeal.resolve", AdminPermission.LISTING_REINSTATE.id(),
                AdminPermission.LISTING_SUSPEND.id()));
        return new Request(Outcome.MODIFIED, ACTION, 0L, 3L,
                "APPEAL_MODIFIED", "Final appeal resolution", replacement,
                "expiry-command", executor, Map.of("origin", "APPEAL_RESOLUTION"),
                confirmed, token, recoveryOnly);
    }

    private static EnforcementRepository.Action action() {
        return new EnforcementRepository.Action(ACTION, TARGET, ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY), 0,
                Instant.EPOCH, null, "POLICY", "Original enforcement", null, 3,
                ADMIN, "Appeal administrator", PREVIEWED_AT.minusSeconds(60), null,
                "correlation", "01ARZ3NDEKTSV4RRFFQ69G93E0", "original-key",
                "a".repeat(64), Map.of());
    }

    private static Result result(String actionId, ActionType type, LifecycleState lifecycle,
            List<EffectiveRestriction> effective) {
        return new Result(actionId, TargetType.LISTING, TARGET, type,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY), lifecycle, Instant.EPOCH, EXPIRES_AT,
                lifecycle == LifecycleState.REVOKED ? 1 : 0, PREVIEWED_AT.minusSeconds(60),
                lifecycle == LifecycleState.REVOKED ? PREVIEWED_AT : null,
                effective, "correlation", true);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceTo(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
