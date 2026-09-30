package com.msb.ecom.auth_service.seed;

import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Request;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.ResetResponse;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Response;
import com.msb.ecom.auth_service.seed.LargeCatalogIdentitySeedContracts.Stats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/demo-fixtures/large-catalog")
@ConditionalOnProperty(name = "demo.large-catalog-seed.enabled", havingValue = "true")
public class LargeCatalogIdentitySeedController {

    private static final String TOKEN = "X-Internal-Service-Token";
    private final LargeCatalogIdentitySeedService service;

    public LargeCatalogIdentitySeedController(LargeCatalogIdentitySeedService service) {
        this.service = service;
    }

    @PostMapping("/identities")
    public Response ensure(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestBody Request request) {
        return service.ensure(suppliedToken, request);
    }

    @GetMapping("/stats")
    public Stats stats(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestParam String namespace) {
        return service.stats(suppliedToken, namespace);
    }

    @DeleteMapping
    public ResetResponse reset(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestParam String namespace) {
        return service.reset(suppliedToken, namespace);
    }
}
