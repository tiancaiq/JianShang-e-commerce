package com.msb.ecom.payment_service.operations;
import org.springframework.beans.factory.annotation.Value;import org.springframework.http.HttpStatus;import org.springframework.web.bind.annotation.*;import org.springframework.web.server.ResponseStatusException;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
@RestController @RequestMapping("/api/v1/internal/system/operations")
public class InternalPaymentOperationsController {private final PaymentOperationsService service;@Value("${commerce.internal-service-token}")private String expected;public InternalPaymentOperationsController(PaymentOperationsService service){this.service=service;}
 @GetMapping public PaymentOperationsContracts.Snapshot snapshot(@RequestHeader(name="X-Internal-Service-Token",required=false)String token){auth(token);return service.snapshot();}
 @PostMapping("/actions") public PaymentOperationsContracts.Action action(@RequestHeader(name="X-Internal-Service-Token",required=false)String token,@RequestBody(required=false)PaymentOperationsContracts.ActionRequest request){auth(token);return service.action(request);}
 private void auth(String supplied){if(expected==null||expected.isBlank()||supplied==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Internal service authorization is required.");}
}
