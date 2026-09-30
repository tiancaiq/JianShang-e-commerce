package com.msb.ecom.product_service.seed;

import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.BatchRequest;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.BatchResponse;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.InventoryCandidatePage;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.ResetResponse;
import com.msb.ecom.product_service.seed.LargeCatalogSeedContracts.Stats;
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
public class LargeCatalogSeedController {

    private static final String TOKEN = "X-Internal-Service-Token";
    private final LargeCatalogSeedService service;

    public LargeCatalogSeedController(LargeCatalogSeedService service) {
        this.service = service;
    }

    @PostMapping("/listings")
    public BatchResponse upsert(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestBody BatchRequest request) {
        return service.upsertBatch(suppliedToken, request);
    }

    @GetMapping("/stats")
    public Stats stats(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestParam String namespace) {
        return service.stats(suppliedToken, namespace);
    }

    @GetMapping("/inventory-candidates")
    public InventoryCandidatePage inventoryCandidates(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestParam String namespace,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return service.inventoryCandidates(suppliedToken, namespace, cursor, limit);
    }

    @DeleteMapping
    public ResetResponse reset(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestParam String namespace) {
        return service.reset(suppliedToken, namespace);
    }
}
