package com.msb.ecom.inventory_service.service;

import com.msb.ecom.inventory_service.repository.InventoryItemRecord;
import com.msb.ecom.inventory_service.repository.InventoryRepository;
import com.msb.ecom.inventory_service.seed.LargeCatalogInventorySeedContracts.BatchRequest;
import com.msb.ecom.inventory_service.seed.LargeCatalogInventorySeedContracts.BatchResponse;
import com.msb.ecom.inventory_service.seed.LargeCatalogInventorySeedContracts.InventoryCandidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "demo.large-catalog-inventory-seed.enabled", havingValue = "true")
public class LargeCatalogInventorySeedService {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern ID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");
    private static final int MAX_BATCH = 1_000;
    private static final int MAX_ON_HAND = 1_000_000;
    private static final String FIXTURE_ACTOR_ID = "00000000000000000000000001";

    private final InventoryRepository repository;
    private final InventoryService inventoryService;
    private final Environment environment;
    private final String expectedToken;

    public LargeCatalogInventorySeedService(
            InventoryRepository repository,
            InventoryService inventoryService,
            Environment environment,
            @Value("${commerce.internal-service-token}") String expectedToken) {
        this.repository = repository;
        this.inventoryService = inventoryService;
        this.environment = environment;
        this.expectedToken = expectedToken;
    }

    @Transactional
    // Initializes only missing Inventory-owned rows; existing balances are preserved after purchases or adjustments.
    public BatchResponse initialize(String suppliedToken, BatchRequest request, String correlationId) {
        requireAllowed(suppliedToken);
        validate(request);
        int initialized = 0;
        int preserved = 0;
        for (InventoryCandidate candidate : request.listings()) {
            InventoryItemRecord existing = repository.findItemByListingId(candidate.listingId()).orElse(null);
            if (existing != null) {
                if (!existing.businessId().equals(candidate.businessId())) {
                    throw new IllegalArgumentException("Seed listing is initialized for another business.");
                }
                preserved++;
                continue;
            }
            inventoryService.initializeSeeded(
                    candidate.businessId(),
                    FIXTURE_ACTOR_ID,
                    candidate.listingId(),
                    "large-catalog-" + request.namespace() + "-" + candidate.listingId(),
                    candidate.suggestedOnHand(),
                    "Opt-in large catalog development inventory.",
                    correlationId == null || correlationId.isBlank() ? "large-catalog-inventory-seed" : correlationId);
            initialized++;
        }
        return new BatchResponse(request.namespace(), request.listings().size(), initialized, preserved);
    }

    private void requireAllowed(String suppliedToken) {
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = suppliedToken == null ? new byte[0] : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("Internal service authentication is required.");
        }
        if (environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("Large catalog inventory seed is unavailable in production.");
        }
    }

    private void validate(BatchRequest request) {
        if (request == null || request.namespace() == null || !NAMESPACE.matcher(request.namespace()).matches()) {
            throw new IllegalArgumentException("Seed namespace is invalid.");
        }
        if (request.listings() == null || request.listings().isEmpty() || request.listings().size() > MAX_BATCH) {
            throw new IllegalArgumentException("Inventory seed batch must contain between 1 and 1000 listings.");
        }
        Set<String> listingIds = new HashSet<>();
        for (InventoryCandidate candidate : request.listings()) {
            if (candidate == null
                    || !validId(candidate.listingId())
                    || !validId(candidate.businessId())
                    || candidate.suggestedOnHand() < 0
                    || candidate.suggestedOnHand() > MAX_ON_HAND) {
                throw new IllegalArgumentException("Inventory seed candidate is invalid.");
            }
            if (!listingIds.add(candidate.listingId())) {
                throw new IllegalArgumentException("Inventory seed batch contains a duplicate listing.");
            }
        }
    }

    private boolean validId(String value) {
        return value != null && ID.matcher(value).matches();
    }
}
