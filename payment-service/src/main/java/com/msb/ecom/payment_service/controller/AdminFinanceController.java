package com.msb.ecom.payment_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.payment_service.dto.AdminFinanceContracts.*;
import com.msb.ecom.payment_service.service.AdminFinanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminFinanceController {
    private final AdminFinanceService service;
    public AdminFinanceController(AdminFinanceService service){this.service=service;}

    @GetMapping("/payments") public Page<PaymentSummary> payments(@RequestParam(required=false)String q,
            @RequestParam(required=false)String orderId,@RequestParam(required=false)String buyerUserId,
            @RequestParam(required=false)String businessId,@RequestParam(required=false)String paymentStatus,
            @RequestParam(required=false)String refundStatus,@RequestParam(required=false)String provider,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant createdFrom,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant createdTo,
            @RequestParam(required=false)BigDecimal amountMin,@RequestParam(required=false)BigDecimal amountMax,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size,
            @RequestParam(defaultValue="createdAt,desc")String sort){return service.searchPayments(q,orderId,buyerUserId,businessId,paymentStatus,refundStatus,provider,createdFrom,createdTo,amountMin,amountMax,page,size,sort);}
    @GetMapping("/payments/{id}") public ResponseEntity<PaymentDetail> payment(@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.payment(id));}
    @GetMapping("/refunds") public Page<RefundSummary> refunds(@RequestParam(required=false)String q,@RequestParam(required=false)String refundId,
            @RequestParam(required=false)String paymentId,@RequestParam(required=false)String orderId,@RequestParam(required=false)String disputeId,
            @RequestParam(required=false)String status,@RequestParam(required=false)String provider,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant createdFrom,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant createdTo,
            @RequestParam(required=false)BigDecimal amountMin,@RequestParam(required=false)BigDecimal amountMax,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size,@RequestParam(defaultValue="createdAt,desc")String sort){return service.searchRefunds(q,refundId,paymentId,orderId,disputeId,status,provider,createdFrom,createdTo,amountMin,amountMax,page,size,sort);}
    @GetMapping("/refunds/{id}") public ResponseEntity<RefundDetail> refund(@PathVariable String id){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refund(id));}
    @PostMapping("/payments/{id}/refund/dry-run") public ResponseEntity<RefundPreview> preview(@PathVariable String id,@RequestBody(required=false)RefundRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.preview(id,request));}
    @PostMapping("/payments/{id}/refund") public ResponseEntity<Object> execute(@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)RefundRequest request,HttpServletRequest servlet){RefundRequest body=request==null?null:new RefundRequest(request.refundType(),request.amount(),request.currency(),request.reasonCode(),request.reason(),request.disputeId(),request.expectedPaymentVersion(),key==null?request.idempotencyKey():key);GovernedRefundResult result=service.submit(id,body,CorrelationIdFilter.current(servlet));Object response=result.approvalRequired()?result.approval():result.execution();return ResponseEntity.status(result.approvalRequired()?202:201).cacheControl(CacheControl.noStore()).body(response);}
}
