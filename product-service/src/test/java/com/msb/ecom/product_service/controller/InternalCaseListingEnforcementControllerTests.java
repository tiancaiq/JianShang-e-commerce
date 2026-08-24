package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.enforcement.EnforcementContracts.ActionType;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.CreateCommand;
import com.msb.ecom.product_service.enforcement.EnforcementContracts.Scope;
import com.msb.ecom.product_service.enforcement.EnforcementService;
import com.msb.ecom.product_service.enforcement.ListingEnforcementApiContracts.CreateRequest;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class InternalCaseListingEnforcementControllerTests {
    private final EnforcementService service = mock(EnforcementService.class);
    private final InternalCaseListingEnforcementController controller =
            new InternalCaseListingEnforcementController(service, "internal-secret");

    @Test
    void trustedDryRunPinsServerRouteCaseAndListingContext() {
        CreateRequest request = new CreateRequest(ActionType.SUSPEND,
                Set.of(Scope.LISTING_PUBLIC_VISIBILITY, Scope.LISTING_PURCHASABILITY),
                "COUNTERFEIT", "Human-reviewed evidence.", null, null, 7L, null,
                Map.of("origin", "INVESTIGATION_CASE"));

        controller.preview("01ARZ3NDEKTSV4RRFFQ69G7AAA", "01ARZ3NDEKTSV4RRFFQ69G7AAB",
                "internal-secret", request);

        ArgumentCaptor<CreateCommand> command = ArgumentCaptor.forClass(CreateCommand.class);
        verify(service).preview(command.capture());
        assertThat(command.getValue().caseId()).isEqualTo("01ARZ3NDEKTSV4RRFFQ69G7AAA");
        assertThat(command.getValue().targetId()).isEqualTo("01ARZ3NDEKTSV4RRFFQ69G7AAB");
        assertThat(command.getValue().expectedTargetVersion()).isEqualTo(7L);
        assertThat(command.getValue().dryRun()).isTrue();
        verify(service, never()).create(any());
    }

    @Test
    void untrustedCallerCannotReachExistingEnforcementService() {
        assertThatThrownBy(() -> controller.create("01ARZ3NDEKTSV4RRFFQ69G7AAA",
                "01ARZ3NDEKTSV4RRFFQ69G7AAB", "wrong-token", null))
                .isInstanceOf(ListingAuthorizationException.class);
        verifyNoInteractions(service);
    }
}
