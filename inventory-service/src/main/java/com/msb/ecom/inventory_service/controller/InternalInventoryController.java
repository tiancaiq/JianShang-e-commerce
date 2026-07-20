package com.msb.ecom.inventory_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.inventory_service.dto.InternalInventoryAvailabilityResponse;
import com.msb.ecom.inventory_service.dto.InventoryReservationReleaseRequest;
import com.msb.ecom.inventory_service.dto.InventoryReservationRequest;
import com.msb.ecom.inventory_service.dto.InventoryReservationResponse;
import com.msb.ecom.inventory_service.service.InternalInventoryAvailabilityService;
import com.msb.ecom.inventory_service.service.InventoryReservationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/v1/internal/inventory")
public class InternalInventoryController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Service-Token";

    private final InternalInventoryAvailabilityService availabilityService;
    private final InventoryReservationService reservationService;

    public InternalInventoryController(
            InternalInventoryAvailabilityService availabilityService,
            InventoryReservationService reservationService) {
        this.availabilityService = availabilityService;
        this.reservationService = reservationService;
    }

    @GetMapping("/{listingId}/availability")
    public InternalInventoryAvailabilityResponse availability(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String listingId) {
        return availabilityService.availability(internalToken, listingId);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public InventoryReservationResponse reserve(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody InventoryReservationRequest request,
            HttpServletRequest servletRequest) {
        return reservationService.reserve(
                internalToken,
                idempotencyKey,
                request,
                CorrelationIdFilter.current(servletRequest));
    }

    @GetMapping("/reservations/{reservationId}")
    public InventoryReservationResponse getReservation(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @PathVariable String reservationId) {
        return reservationService.get(internalToken, reservationId);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public InventoryReservationResponse release(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable String reservationId,
            @RequestBody InventoryReservationReleaseRequest request,
            HttpServletRequest servletRequest) {
        return reservationService.release(
                internalToken,
                reservationId,
                idempotencyKey,
                request,
                CorrelationIdFilter.current(servletRequest));
    }

    @PostMapping("/reservations/{reservationId}/commit")
    public InventoryReservationResponse commit(
            @RequestHeader(name = INTERNAL_TOKEN_HEADER, required = false) String internalToken,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable String reservationId,
            HttpServletRequest servletRequest) {
        return reservationService.commit(
                internalToken,
                reservationId,
                idempotencyKey,
                CorrelationIdFilter.current(servletRequest));
    }
}
