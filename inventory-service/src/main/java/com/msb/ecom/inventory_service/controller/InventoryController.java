package com.msb.ecom.inventory_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.common.web.http.IfMatchVersion;
import com.msb.ecom.inventory_service.dto.ApiDataResponse;
import com.msb.ecom.inventory_service.dto.InventoryAdjustmentRequest;
import com.msb.ecom.inventory_service.dto.InventoryCatalogPageResponse;
import com.msb.ecom.inventory_service.dto.InventoryInitializeRequest;
import com.msb.ecom.inventory_service.dto.InventoryMovementPageResponse;
import com.msb.ecom.inventory_service.dto.InventoryResponse;
import com.msb.ecom.inventory_service.service.InventoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private static final String VERSION_REQUIRED = "If-Match inventory version is required.";

    private final InventoryService inventoryService;

    @GetMapping
    public InventoryCatalogPageResponse list(
            @PathVariable String businessId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String listingStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return inventoryService.list(businessId, q, listingStatus, cursor, limit);
    }

    @PostMapping("/{listingId}/initialize")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiDataResponse<InventoryResponse> initialize(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InventoryInitializeRequest request,
            HttpServletRequest servletRequest) {
        return new ApiDataResponse<>(inventoryService.initialize(
                businessId,
                listingId,
                idempotencyKey,
                request,
                CorrelationIdFilter.current(servletRequest)));
    }

    @GetMapping("/{listingId}")
    public ApiDataResponse<InventoryResponse> get(
            @PathVariable String businessId,
            @PathVariable String listingId) {
        return new ApiDataResponse<>(inventoryService.get(businessId, listingId));
    }

    @PostMapping("/{listingId}/adjustments")
    public ApiDataResponse<InventoryResponse> adjust(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InventoryAdjustmentRequest request,
            HttpServletRequest servletRequest) {
        return new ApiDataResponse<>(inventoryService.adjust(
                businessId,
                listingId,
                IfMatchVersion.parseRequired(ifMatch, VERSION_REQUIRED),
                idempotencyKey,
                request,
                CorrelationIdFilter.current(servletRequest)));
    }

    @GetMapping("/{listingId}/movements")
    public InventoryMovementPageResponse movements(
            @PathVariable String businessId,
            @PathVariable String listingId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return inventoryService.movements(businessId, listingId, cursor, limit);
    }
}
