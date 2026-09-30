package com.msb.ecom.inventory_service.seed;

import com.msb.ecom.inventory_service.seed.LargeCatalogInventorySeedContracts.BatchRequest;
import com.msb.ecom.inventory_service.seed.LargeCatalogInventorySeedContracts.BatchResponse;
import com.msb.ecom.inventory_service.service.LargeCatalogInventorySeedService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/demo-fixtures/large-catalog")
@ConditionalOnProperty(name = "demo.large-catalog-inventory-seed.enabled", havingValue = "true")
public class LargeCatalogInventorySeedController {

    private static final String TOKEN = "X-Internal-Service-Token";
    private final LargeCatalogInventorySeedService service;

    public LargeCatalogInventorySeedController(LargeCatalogInventorySeedService service) {
        this.service = service;
    }

    @PostMapping("/inventory")
    public BatchResponse initialize(
            @RequestHeader(name = TOKEN, required = false) String suppliedToken,
            @RequestBody BatchRequest request,
            HttpServletRequest servletRequest) {
        return service.initialize(suppliedToken, request, servletRequest.getHeader("X-Correlation-Id"));
    }
}
