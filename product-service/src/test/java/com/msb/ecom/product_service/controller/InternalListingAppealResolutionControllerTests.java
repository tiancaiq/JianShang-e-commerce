package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.enforcement.ListingAppealResolutionContracts.Request;
import com.msb.ecom.product_service.enforcement.ListingAppealResolutionService;
import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class InternalListingAppealResolutionControllerTests {
    private static final String APPEAL_ID = "01ARZ3NDEKTSV4RRFFQ69G8AAA";
    private final ListingAppealResolutionService service = mock(ListingAppealResolutionService.class);
    private final InternalListingAppealResolutionController controller =
            new InternalListingAppealResolutionController(service, "trusted-token");

    @Test
    void rejectsMissingOrIncorrectInternalTokenBeforeServiceInvocation() {
        assertThatThrownBy(() -> controller.preview(APPEAL_ID, null, null))
                .isInstanceOf(ListingAuthorizationException.class);
        assertThatThrownBy(() -> controller.execute(APPEAL_ID, "wrong", null))
                .isInstanceOf(ListingAuthorizationException.class);
        verifyNoInteractions(service);
    }

    @Test
    void trustedRoutesKeepPreviewAndExecutionSeparate() {
        Request request = mock(Request.class);
        controller.preview(APPEAL_ID, "trusted-token", request);
        controller.execute(APPEAL_ID, "trusted-token", request);
        verify(service).preview(APPEAL_ID, request);
        verify(service).execute(APPEAL_ID, request);
    }
}
