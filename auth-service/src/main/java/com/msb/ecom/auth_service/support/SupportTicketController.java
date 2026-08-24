package com.msb.ecom.auth_service.support;

import com.msb.ecom.auth_service.dto.ApiDataResponse;
import com.msb.ecom.common.web.correlation.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.msb.ecom.auth_service.support.SupportContracts.*;

@RestController
@RequestMapping("/api/v1/support/tickets")
@RequiredArgsConstructor
public class SupportTicketController {
    private final SupportService service;

    @PostMapping
    ResponseEntity<ApiDataResponse<UserDetail>> create(@RequestHeader(name="Idempotency-Key",required=false)String key,
            @RequestBody(required=false)CreateTicketRequest request,HttpServletRequest http){return ResponseEntity.status(HttpStatus.CREATED)
            .body(new ApiDataResponse<>(service.create(request,key,CorrelationIdFilter.current(http))));}
    @GetMapping("/mine") ApiDataResponse<UserPage> mine(@RequestParam(defaultValue="0")int page,
            @RequestParam(defaultValue="25")int size){return new ApiDataResponse<>(service.mine(page,size));}
    @GetMapping("/{ticketId}") ApiDataResponse<UserDetail> detail(@PathVariable String ticketId){return new ApiDataResponse<>(service.userDetail(ticketId));}
    @PostMapping("/{ticketId}/messages") ApiDataResponse<UserDetail> message(@PathVariable String ticketId,
            @RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody(required=false)MessageRequest request,
            HttpServletRequest http){return new ApiDataResponse<>(service.userMessage(ticketId,request,key,CorrelationIdFilter.current(http)));}
}
