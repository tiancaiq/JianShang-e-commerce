package com.msb.ecom.order_service.controller;

import com.msb.ecom.order_service.dto.BuyerOrderDetailResponse;
import com.msb.ecom.order_service.dto.BuyerOrderPageResponse;
import com.msb.ecom.order_service.service.BuyerOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class BuyerOrderController {

    private final BuyerOrderService service;

    public BuyerOrderController(BuyerOrderService service) {
        this.service = service;
    }

    @GetMapping
    public BuyerOrderPageResponse list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit) {
        return service.list(cursor, limit);
    }

    @GetMapping("/{orderId}")
    public BuyerOrderDetailResponse detail(@PathVariable String orderId) {
        return service.detail(orderId);
    }
}
