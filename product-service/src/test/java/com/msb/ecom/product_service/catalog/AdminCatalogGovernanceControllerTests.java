package com.msb.ecom.product_service.catalog;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminCatalogGovernanceControllerTests {
    @Test
    void pendingApprovalDoesNotDispatchCatalogMutation() {
        CatalogService service = mock(CatalogService.class);
        AdminCatalogController controller = new AdminCatalogController(service);
        ChangeCategoryStatusRequest request = new ChangeCategoryStatusRequest(
                4, CategoryStatus.DISABLED, "High-impact policy change");
        ApprovalReference reference = new ApprovalReference(
                "01K00000000000000000000001", "HIGH_IMPACT_CATALOG_CATEGORY_DISABLE",
                "HIGH", "CATEGORY", "01K00000000000000000000002", 1, 0,
                "PENDING", Instant.parse("2026-08-23T00:00:00Z"),
                Instant.parse("2026-08-23T08:00:00Z"), 0);
        CatalogApproval approval = new CatalogApproval("PENDING_APPROVAL", reference, true,
                "Independent approval is required.");
        when(service.evaluateStatusGovernance(reference.targetId(), "catalog-governance-1", request))
                .thenReturn(approval);

        var response = controller.status(reference.targetId(), "catalog-governance-1", request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(approval);
        verify(service, never()).changeStatus(any(), any(), any());
    }
}
