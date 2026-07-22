package com.msb.ecom.order_service.service;

import com.msb.ecom.common.core.validation.FixedLengthIds;
import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.dto.CartCurrencyTotalResponse;
import com.msb.ecom.order_service.dto.CartItemResponse;
import com.msb.ecom.order_service.dto.CartResponse;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.repository.CartRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CurrentActorProvider currentActorProvider;
    private final CartRepository repository;
    private final ProductCommerceClient productClient;
    private final InventoryAvailabilityClient inventoryClient;
    private final Clock clock;
    private final int maxQuantity;

    @Autowired
    public CartService(
            CurrentActorProvider currentActorProvider,
            CartRepository repository,
            ProductCommerceClient productClient,
            InventoryAvailabilityClient inventoryClient,
            @Value("${cart.max-quantity:999}") int maxQuantity) {
        this(currentActorProvider, repository, productClient, inventoryClient, Clock.systemUTC(), maxQuantity);
    }

    CartService(
            CurrentActorProvider currentActorProvider,
            CartRepository repository,
            ProductCommerceClient productClient,
            InventoryAvailabilityClient inventoryClient,
            Clock clock,
            int maxQuantity) {
        this.currentActorProvider = currentActorProvider;
        this.repository = repository;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.clock = clock;
        this.maxQuantity = maxQuantity;
    }

    public CartResponse get() {
        return response(repository.get(userId()));
    }

    // Adds or replaces one business item only after current catalog and stock checks.
    public CartResponse add(String listingId, int quantity) {
        String userId = userId();
        String normalizedListingId = listingId(listingId);
        requireQuantity(quantity);
        ProductCommerceClient.ProductContext product = requireEligibleProduct(normalizedListingId);
        requireAvailable(product, quantity);
        CartDocument cart = repository.upsert(userId, new CartStoredItem(
                normalizedListingId,
                quantity,
                product.priceAmount(),
                product.currency(),
                clock.instant()));
        log.info("Cart item stored userId={} listingId={} quantity={} version={}",
                userId, normalizedListingId, quantity, cart.version());
        return response(cart);
    }

    // Replaces quantity while preserving the originally observed cart price.
    public CartResponse update(String listingId, int quantity) {
        String userId = userId();
        String normalizedListingId = listingId(listingId);
        requireQuantity(quantity);
        ProductCommerceClient.ProductContext product = requireEligibleProduct(normalizedListingId);
        requireAvailable(product, quantity);
        CartDocument cart = repository.replaceQuantity(userId, normalizedListingId, quantity);
        log.info("Cart quantity replaced userId={} listingId={} quantity={} version={}",
                userId, normalizedListingId, quantity, cart.version());
        return response(cart);
    }

    public CartResponse remove(String listingId) {
        String userId = userId();
        String normalizedListingId = listingId(listingId);
        CartDocument cart = repository.remove(userId, normalizedListingId);
        log.info("Cart item removed userId={} listingId={} version={}",
                userId, normalizedListingId, cart.version());
        return response(cart);
    }

    public CartResponse clear() {
        String userId = userId();
        repository.clear(userId);
        log.info("Cart cleared userId={}", userId);
        return response(CartDocument.empty());
    }

    private ProductCommerceClient.ProductContext requireEligibleProduct(String listingId) {
        ProductCommerceClient.ProductContext product = productClient.find(listingId)
                .orElseThrow(() -> new CartException(
                        HttpStatus.CONFLICT,
                        "CART_ITEM_NOT_ELIGIBLE",
                        "Only active business listings can be added to the cart."));
        if (!"BUSINESS".equals(product.sellerType())
                || !"ACTIVE".equals(product.status())
                || product.priceAmount() == null
                || product.priceAmount().signum() < 0
                || product.currency() == null
                || product.currency().isBlank()) {
            throw new CartException(
                    HttpStatus.CONFLICT,
                    "CART_ITEM_NOT_ELIGIBLE",
                    "Only active business listings can be added to the cart.");
        }
        return product;
    }

    private void requireAvailable(ProductCommerceClient.ProductContext product, int quantity) {
        InventoryAvailabilityClient.Availability availability = inventoryClient.get(product.listingId());
        if (!availability.initialized()
                || availability.businessId() == null
                || !availability.businessId().equals(product.businessId())
                || availability.available() < quantity) {
            throw new CartException(
                    HttpStatus.CONFLICT,
                    "CART_INSUFFICIENT_STOCK",
                    "The requested quantity is not currently available.");
        }
    }

    private CartResponse response(CartDocument cart) {
        List<CartItemResponse> items = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        int totalQuantity = 0;
        for (CartStoredItem item : cart.items()) {
            ProductCommerceClient.ProductContext product = displayProduct(item.listingId());
            items.add(new CartItemResponse(
                    item.listingId(),
                    product == null ? "Business item" : product.title(),
                    product == null ? null : product.thumbnailUrl(),
                    product == null ? null : product.storeName(),
                    product == null ? null : product.storeSlug(),
                    product == null ? null : product.businessVerified(),
                    product == null ? null : product.publicCity(),
                    product == null ? null : product.publicRegion(),
                    item.quantity(),
                    item.observedPrice(),
                    item.currency(),
                    item.addedAt()));
            totalQuantity += item.quantity();
            totals.merge(
                    item.currency(),
                    item.observedPrice().multiply(BigDecimal.valueOf(item.quantity())),
                    BigDecimal::add);
        }
        List<CartCurrencyTotalResponse> totalResponses = totals.entrySet().stream()
                .map(entry -> new CartCurrencyTotalResponse(entry.getKey(), entry.getValue()))
                .toList();
        return new CartResponse(
                cart.version(),
                cart.expiresAt(),
                items.size(),
                totalQuantity,
                totalResponses,
                List.copyOf(items));
    }

    private ProductCommerceClient.ProductContext displayProduct(String listingId) {
        try {
            return productClient.find(listingId).orElse(null);
        } catch (CartException exception) {
            log.warn("Cart display context unavailable listingId={} code={}", listingId, exception.code());
            return null;
        }
    }

    private String userId() {
        return currentActorProvider.currentActor().subject();
    }

    private String listingId(String listingId) {
        return FixedLengthIds.requireTrimmed("Listing ID", listingId, 26);
    }

    private void requireQuantity(int quantity) {
        if (quantity < 1 || quantity > maxQuantity) {
            throw new IllegalArgumentException("Quantity must be between 1 and " + maxQuantity + ".");
        }
    }
}
