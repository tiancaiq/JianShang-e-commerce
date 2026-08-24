package com.msb.ecom.order_service.controller;

import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import com.msb.ecom.order_service.dto.OrderDisputeContracts.*;
import com.msb.ecom.order_service.service.AdminDisputeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/admin/disputes")
public class AdminDisputeController {
    private final AdminDisputeService disputes;
    public AdminDisputeController(AdminDisputeService disputes){this.disputes=disputes;}
    @GetMapping public Page search(@RequestParam(required=false)String q,@RequestParam(required=false)String orderId,
            @RequestParam(required=false)String buyerUserId,@RequestParam(required=false)String businessId,
            @RequestParam(required=false)Status status,@RequestParam(required=false)String reasonCode,
            @RequestParam(required=false)Priority priority,@RequestParam(defaultValue="ALL")Assignment assignment,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant createdFrom,
            @RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant createdTo,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size,
            @RequestParam(defaultValue="createdAt,desc")String sort){return disputes.search(q,orderId,buyerUserId,businessId,status,reasonCode,priority,assignment,createdFrom,createdTo,page,size,sort);}
    @GetMapping("/{id}") public ResponseEntity<Detail> detail(@PathVariable String id){Detail d=disputes.detail(id);return ResponseEntity.ok().eTag(Long.toString(d.summary().version())).cacheControl(CacheControl.noStore()).body(d);}
    @PostMapping("/{id}/claim") public Detail claim(@PathVariable String id,@RequestBody(required=false)VersionRequest body,HttpServletRequest r){return disputes.claim(id,body,CorrelationIdFilter.current(r));}
    @PostMapping("/{id}/release") public Detail release(@PathVariable String id,@RequestBody(required=false)VersionRequest body,HttpServletRequest r){return disputes.release(id,body,CorrelationIdFilter.current(r));}
    @PatchMapping("/{id}/priority") public Detail priority(@PathVariable String id,@RequestBody(required=false)PriorityRequest body,HttpServletRequest r){return disputes.priority(id,body,CorrelationIdFilter.current(r));}
    @PostMapping("/{id}/notes") public Detail note(@PathVariable String id,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)NoteRequest body,HttpServletRequest r){return disputes.note(id,body==null?null:new NoteRequest(body.body(),key==null?body.idempotencyKey():key),CorrelationIdFilter.current(r));}
    @PostMapping("/{id}/request-information") public Detail information(@PathVariable String id,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)InformationRequest body,HttpServletRequest r){return disputes.requestInformation(id,body==null?null:new InformationRequest(body.party(),body.message(),body.expectedVersion(),key==null?body.idempotencyKey():key),CorrelationIdFilter.current(r));}
    @PostMapping("/{id}/ready-for-decision") public Detail ready(@PathVariable String id,@RequestBody(required=false)ReasonRequest body,HttpServletRequest r){return disputes.ready(id,body,CorrelationIdFilter.current(r));}
    @PostMapping("/{id}/resolve") public Detail resolve(@PathVariable String id,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)ResolveRequest body,HttpServletRequest r){return disputes.resolve(id,body==null?null:new ResolveRequest(body.resolutionType(),body.reasonCode(),body.reason(),body.recommendedRefundAmount(),body.returnInstructions(),body.returnDeadline(),body.expectedVersion(),key==null?body.idempotencyKey():key),CorrelationIdFilter.current(r));}
}
