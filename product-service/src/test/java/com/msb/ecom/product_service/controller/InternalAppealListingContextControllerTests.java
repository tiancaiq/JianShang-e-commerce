package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.InternalAppealListingContext;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import com.msb.ecom.product_service.service.AppealListingContextService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InternalAppealListingContextControllerTests {
    private final AppealListingContextService service = mock(AppealListingContextService.class);
    private final InternalAppealListingContextController controller =
            new InternalAppealListingContextController(service, "trusted-token");

    @Test
    void rejectsMissingInternalToken() {
        assertThatThrownBy(() -> controller.byAction("01ARZ3NDEKTSV4RRFFQ69G5AAA", null))
                .isInstanceOf(ListingAuthorizationException.class);
        verifyNoInteractions(service);
    }

    @Test
    void trustedCallerReceivesOnlyAllowListedOwnershipAndEnforcementContext() {
        InternalAppealListingContext context = new InternalAppealListingContext(
                "01ARZ3NDEKTSV4RRFFQ69G5AAA", "01ARZ3NDEKTSV4RRFFQ69G5AAB", "Safe listing",
                "INDIVIDUAL", "01ARZ3NDEKTSV4RRFFQ69G5AAC", null, "ACTIVE", 2,
                "SUSPEND", List.of("LISTING_PUBLIC_VISIBILITY"), "ACTIVE", Instant.EPOCH,
                null, 0, Instant.EPOCH, "POLICY", "Internal reason", null, "SUSPENDED");
        when(service.byActionId(context.enforcementActionId())).thenReturn(context);

        assertThat(controller.byAction(context.enforcementActionId(), "trusted-token")).isEqualTo(context);
    }

    @Test
    void actorListingQueryUsesServerSuppliedOwnerSet() {
        controller.mine(new InternalAppealListingContextController.MineRequest(
                "01ARZ3NDEKTSV4RRFFQ69G5AAC", Set.of("01ARZ3NDEKTSV4RRFFQ69G5AAD")), "trusted-token");
        verify(service).activeForActor("01ARZ3NDEKTSV4RRFFQ69G5AAC", Set.of("01ARZ3NDEKTSV4RRFFQ69G5AAD"));
    }
}
