package com.msb.ecom.product_service.catalog;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;

interface CatalogGovernanceClient {
    CatalogApproval evaluateCategoryDisable(String accessToken, CategorySummary category,
                                            ImpactPreview impact,
                                            ChangeCategoryStatusRequest request,
                                            String idempotencyKey);
}
