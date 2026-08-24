package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.*;
import com.msb.ecom.order_service.service.OrderDisputeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class OrderDisputeController {
    private final OrderDisputeService disputes;
    public OrderDisputeController(OrderDisputeService disputes){this.disputes=disputes;}

    @PostMapping("/api/v1/orders/{orderId}/disputes")
    public ResponseEntity<Detail> createBuyer(@PathVariable String orderId,
            @RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)CreateRequest body,
            HttpServletRequest request){return ResponseEntity.status(201).body(disputes.createBuyer(orderId,withKey(body,key),CorrelationIdFilter.current(request)));}
    @GetMapping("/api/v1/orders/{orderId}/disputes")
    public List<Summary> buyerList(@PathVariable String orderId){return disputes.buyerList(orderId);}
    @GetMapping("/api/v1/disputes/{disputeId}")
    public Detail buyerDetail(@PathVariable String disputeId){return disputes.buyerDetail(disputeId);}
    @PostMapping("/api/v1/disputes/{disputeId}/statements")
    public Detail buyerStatement(@PathVariable String disputeId,@RequestHeader(name="Idempotency-Key",required=false)String key,
            @RequestBody(required=false)StatementRequest body,HttpServletRequest request){return disputes.buyerStatement(disputeId,withKey(body,key),CorrelationIdFilter.current(request));}

    @PostMapping("/api/v1/businesses/{businessId}/disputes")
    public ResponseEntity<Detail> createSeller(@PathVariable String businessId,
            @RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)CreateRequest body,
            HttpServletRequest request){return ResponseEntity.status(201).body(disputes.createSeller(businessId,withKey(body,key),CorrelationIdFilter.current(request)));}
    @GetMapping("/api/v1/businesses/{businessId}/disputes/{disputeId}")
    public Detail sellerDetail(@PathVariable String businessId,@PathVariable String disputeId){return disputes.sellerDetail(businessId,disputeId);}
    @PostMapping("/api/v1/businesses/{businessId}/disputes/{disputeId}/statements")
    public Detail sellerStatement(@PathVariable String businessId,@PathVariable String disputeId,
            @RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)StatementRequest body,
            HttpServletRequest request){return disputes.sellerStatement(businessId,disputeId,withKey(body,key),CorrelationIdFilter.current(request));}

    private CreateRequest withKey(CreateRequest b,String key){return b==null?null:new CreateRequest(b.businessGroupId(),b.reasonCode(),b.description(),b.itemIds(),b.evidence(),key==null?b.idempotencyKey():key);}
    private StatementRequest withKey(StatementRequest b,String key){return b==null?null:new StatementRequest(b.body(),b.evidence(),key==null?b.idempotencyKey():key);}
}
