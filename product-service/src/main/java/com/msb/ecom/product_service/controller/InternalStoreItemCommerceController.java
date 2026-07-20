package com.msb.ecom.product_service.controller;

import com.msb.ecom.product_service.dto.BusinessStoreItemCommerceContextResponse;
import com.msb.ecom.product_service.service.BusinessStoreItemCommerceContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/store/items")
@RequiredArgsConstructor
public class InternalStoreItemCommerceController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final BusinessStoreItemCommerceContextService commerceContextService;

    @GetMapping("/{listingId}/commerce-context")
    public BusinessStoreItemCommerceContextResponse item(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String listingId) {
        return commerceContextService.item(internalToken, listingId);
    }
}
