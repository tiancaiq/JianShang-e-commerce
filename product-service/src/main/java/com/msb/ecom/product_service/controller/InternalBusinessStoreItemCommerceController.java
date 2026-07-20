package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextPageResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextResponse;
import com.msb.ecom.product_service.dto.BusinessStoreItemSearchRequest;
import com.msb.ecom.product_service.service.BusinessStoreItemCommerceContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/businesses/{businessId}/store/items")
@RequiredArgsConstructor
public class InternalBusinessStoreItemCommerceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final BusinessStoreItemCommerceContextService commerceContextService;

    @GetMapping("/commerce-context")
    public BusinessStoreItemCommerceContextPageResponse page(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String businessId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return commerceContextService.page(
                internalToken,
                businessId,
                new BusinessStoreItemSearchRequest(q, status, cursor, limit));
    }

    @GetMapping("/{listingId}/commerce-context")
    public BusinessStoreItemCommerceContextResponse item(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String businessId,
            @PathVariable String listingId) {
        return commerceContextService.item(internalToken, businessId, listingId);
    }
}
