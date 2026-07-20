package com.msb.ecom.order_service.controller;

import com.msb.ecom.order_service.dto.BusinessOrderDetailResponse;
import com.msb.ecom.order_service.dto.BusinessOrderPageResponse;
import com.msb.ecom.order_service.service.BusinessOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/orders")
public class BusinessOrderController {

    private final BusinessOrderService service;

    public BusinessOrderController(BusinessOrderService service) {
        this.service = service;
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
}
