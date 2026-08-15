package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.BusinessOrderAcceptanceResponse;
import com.msb.ecom.order_service.dto.BusinessOrderDetailResponse;
import com.msb.ecom.order_service.dto.BusinessOrderFulfillmentResponse;
import com.msb.ecom.order_service.dto.BusinessOrderPageResponse;
import com.msb.ecom.order_service.dto.CreateManualShipmentRequest;
import com.msb.ecom.order_service.service.BusinessOrderAcceptanceService;
import com.msb.ecom.order_service.service.BusinessOrderFulfillmentService;
import com.msb.ecom.order_service.service.BusinessOrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/orders")
public class BusinessOrderController {

    private final BusinessOrderService service;
    private final BusinessOrderAcceptanceService acceptanceService;
    private final BusinessOrderFulfillmentService fulfillmentService;

    public BusinessOrderController(
            BusinessOrderService service,
            BusinessOrderAcceptanceService acceptanceService,
            BusinessOrderFulfillmentService fulfillmentService) {
        this.service = service;
        this.acceptanceService = acceptanceService;
        this.fulfillmentService = fulfillmentService;
    }

    @GetMapping
    public BusinessOrderPageResponse list(
            @PathVariable String businessId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return service.list(businessId, status, cursor, limit);
    }

    @GetMapping("/{businessOrderId}")
    public BusinessOrderDetailResponse detail(
            @PathVariable String businessId,
            @PathVariable String businessOrderId) {
        return service.detail(businessId, businessOrderId);
    }

    @PostMapping("/{businessOrderId}/accept")
    public ResponseEntity<BusinessOrderAcceptanceResponse> accept(
            @PathVariable String businessId,
            @PathVariable String businessOrderId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        BusinessOrderAcceptanceResponse response = acceptanceService.accept(
                businessId,
                businessOrderId,
                ifMatch,
                idempotencyKey,
                CorrelationIdFilter.current(request));
        return ResponseEntity.ok()
                .eTag(Long.toString(response.version()))
                .body(response);
    }

    @PostMapping("/{businessOrderId}/processing")
    public ResponseEntity<BusinessOrderFulfillmentResponse> startProcessing(
            @PathVariable String businessId,
            @PathVariable String businessOrderId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        BusinessOrderFulfillmentResponse response = fulfillmentService.startProcessing(
                businessId, businessOrderId, ifMatch, idempotencyKey,
                CorrelationIdFilter.current(request));
        return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }

    @PostMapping("/{businessOrderId}/shipments")
    public ResponseEntity<BusinessOrderFulfillmentResponse> createShipment(
            @PathVariable String businessId,
            @PathVariable String businessOrderId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) CreateManualShipmentRequest body,
            HttpServletRequest request) {
        BusinessOrderFulfillmentResponse response = fulfillmentService.createShipment(
                businessId, businessOrderId, ifMatch, idempotencyKey, body,
                CorrelationIdFilter.current(request));
        return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }

    @PostMapping("/{businessOrderId}/delivery-demo")
    public ResponseEntity<BusinessOrderFulfillmentResponse> recordDemoDelivery(
            @PathVariable String businessId,
            @PathVariable String businessOrderId,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        BusinessOrderFulfillmentResponse response = fulfillmentService.recordDemoDelivery(
                businessId, businessOrderId, ifMatch, idempotencyKey,
                CorrelationIdFilter.current(request));
        return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }
}
