package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.BusinessOrderReturnResponse;
import com.msb.ecom.order_service.dto.CreateBusinessOrderReturnRequest;
import com.msb.ecom.order_service.dto.ReceiveBusinessOrderReturnRequest;
import com.msb.ecom.order_service.service.BusinessOrderReturnService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class BusinessOrderReturnController {
    private final BusinessOrderReturnService service;
    public BusinessOrderReturnController(BusinessOrderReturnService service){this.service=service;}

    @GetMapping("/api/v1/orders/{orderId}/groups/{groupId}/return")
    public ResponseEntity<BusinessOrderReturnResponse> buyerDetail(@PathVariable String orderId,@PathVariable String groupId){
        var response=service.buyerDetail(orderId,groupId);return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }
    @PostMapping("/api/v1/orders/{orderId}/groups/{groupId}/returns")
    public ResponseEntity<BusinessOrderReturnResponse> request(@PathVariable String orderId,@PathVariable String groupId,
            @RequestHeader(name=HttpHeaders.IF_MATCH,required=false) String version,
            @RequestHeader(name="Idempotency-Key",required=false) String key,
            @RequestBody(required=false) CreateBusinessOrderReturnRequest body,HttpServletRequest request){
        var response=service.request(orderId,groupId,version,key,body,CorrelationIdFilter.current(request));
        return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }
    @GetMapping("/api/v1/businesses/{businessId}/orders/{groupId}/return")
    public ResponseEntity<BusinessOrderReturnResponse> sellerDetail(@PathVariable String businessId,@PathVariable String groupId){
        var response=service.sellerDetail(businessId,groupId);
        return response==null?ResponseEntity.noContent().build():ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }
    @PostMapping("/api/v1/businesses/{businessId}/orders/{groupId}/returns/{returnId}/authorize")
    public ResponseEntity<BusinessOrderReturnResponse> authorize(@PathVariable String businessId,@PathVariable String groupId,
            @PathVariable String returnId,@RequestHeader(name=HttpHeaders.IF_MATCH,required=false) String version,
            @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request){
        var response=service.authorize(businessId,groupId,returnId,version,key,CorrelationIdFilter.current(request));
        return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }
    @PostMapping("/api/v1/businesses/{businessId}/orders/{groupId}/returns/{returnId}/receive")
    public ResponseEntity<BusinessOrderReturnResponse> receive(@PathVariable String businessId,@PathVariable String groupId,
            @PathVariable String returnId,@RequestHeader(name=HttpHeaders.IF_MATCH,required=false) String version,
            @RequestHeader(name="Idempotency-Key",required=false) String key,
            @RequestBody(required=false) ReceiveBusinessOrderReturnRequest body,HttpServletRequest request){
        var response=service.receive(businessId,groupId,returnId,version,key,body,CorrelationIdFilter.current(request));
        return ResponseEntity.ok().eTag(Long.toString(response.version())).body(response);
    }
}
