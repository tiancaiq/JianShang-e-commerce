package com.msb.ecom.product_service.catalog;

import com.msb.ecom.product_service.model.ListingAuthorizationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static com.msb.ecom.product_service.catalog.CatalogContracts.*;

@RestController
@RequestMapping("/api/v1/internal/admin/catalog/categories")
public class InternalGovernedCatalogController {
    private static final String INTERNAL_TOKEN = "X-Internal-Service-Token";
    private final CatalogService service;
    private final byte[] expectedToken;

    public InternalGovernedCatalogController(CatalogService service,
            @Value("${commerce.internal-service-token}") String token) {
        this.service = service;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/{categoryId}/status")
    public CategoryDetail execute(@PathVariable String categoryId,
            @RequestHeader(name = INTERNAL_TOKEN, required = false) String suppliedToken,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @RequestBody ChangeCategoryStatusRequest request) {
        requireInternal(suppliedToken);
        return service.changeStatus(categoryId, idempotencyKey, request);
    }

    private void requireInternal(String supplied) {
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length == 0 || !MessageDigest.isEqual(expectedToken, actual)) {
            throw new ListingAuthorizationException(
                    "Internal governance execution authentication is required.");
        }
    }
}
