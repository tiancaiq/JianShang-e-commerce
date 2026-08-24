package com.msb.ecom.product_service.operations;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/v1/internal/system/operations")
@RequiredArgsConstructor
public class InternalProductOperationsController {
    private final ProductOperationsService service;
    @Value("${commerce.internal-service-token}")
    private String expectedToken;

    @GetMapping
    public ProductOperationsContracts.Snapshot snapshot(
            @RequestHeader(name = "X-Internal-Service-Token", required = false) String token) {
        authenticate(token);
        return service.snapshot();
    }

    @PostMapping("/actions")
    public ProductOperationsContracts.Action action(
            @RequestHeader(name = "X-Internal-Service-Token", required = false) String token,
            @RequestBody(required = false) ProductOperationsContracts.ActionRequest request) {
        authenticate(token);
        return service.action(request);
    }

    private void authenticate(String supplied) {
        if (!StringUtils.hasText(expectedToken) || !StringUtils.hasText(supplied)
                || !MessageDigest.isEqual(expectedToken.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal service authorization is required.");
        }
    }
}
