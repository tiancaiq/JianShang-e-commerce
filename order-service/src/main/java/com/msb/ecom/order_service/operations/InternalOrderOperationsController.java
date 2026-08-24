package com.msb.ecom.order_service.operations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController @RequestMapping("/api/v1/internal/system/operations")
public class InternalOrderOperationsController {
    private final OrderOperationsService service;
    @Value("${commerce.internal-service-token}") private String expected;
    public InternalOrderOperationsController(OrderOperationsService service) { this.service = service; }
    @GetMapping public OrderOperationsContracts.Snapshot snapshot(@RequestHeader(name="X-Internal-Service-Token",required=false)String token){auth(token);return service.snapshot();}
    @PostMapping("/actions") public OrderOperationsContracts.Action action(@RequestHeader(name="X-Internal-Service-Token",required=false)String token,@RequestBody(required=false)OrderOperationsContracts.ActionRequest request){auth(token);return service.action(request);}
    private void auth(String supplied){if(expected==null||expected.isBlank()||supplied==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Internal service authorization is required.");}
}
