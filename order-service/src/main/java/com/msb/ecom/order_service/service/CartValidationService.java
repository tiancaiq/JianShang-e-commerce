package com.msb.ecom.order_service.service;

import com.msb.ecom.common.web.security.CurrentActorProvider;
import com.msb.ecom.order_service.dto.CartCurrencyTotalResponse;
import com.msb.ecom.order_service.dto.CartValidationIssueResponse;
import com.msb.ecom.order_service.dto.CartValidationItemResponse;
import com.msb.ecom.order_service.dto.CartValidationResponse;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartException;
import com.msb.ecom.order_service.model.CartStoredItem;
import com.msb.ecom.order_service.repository.CartRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class CartValidationService {

    private static final Logger log = LoggerFactory.getLogger(CartValidationService.class);

    private static final String LISTING_UNAVAILABLE = "CART_LISTING_UNAVAILABLE";
    private static final String SELLER_UNAVAILABLE = "CART_SELLER_UNAVAILABLE";
    private static final String OUT_OF_STOCK = "CART_OUT_OF_STOCK";
    private static final String QUANTITY_REDUCED = "CART_QUANTITY_REDUCED";
    private static final String CURRENCY_CONFLICT = "CART_CURRENCY_CONFLICT";
    private static final String PRICE_CHANGED = "CART_PRICE_CHANGED";
    private static final String CURRENCY_CHANGED = "CART_CURRENCY_CHANGED";

    private static final Map<String, Integer> ISSUE_PRIORITY = Map.of(
            LISTING_UNAVAILABLE, 0,
            SELLER_UNAVAILABLE, 1,
            OUT_OF_STOCK, 2,
            QUANTITY_REDUCED, 3,
            CURRENCY_CONFLICT, 4,
            PRICE_CHANGED, 5,
            CURRENCY_CHANGED, 5);

    private final CurrentActorProvider currentActorProvider;
    private final CartRepository repository;
    private final ProductCommerceClient productClient;
    private final InventoryAvailabilityClient inventoryClient;
    private final BusinessStoreEligibilityClient eligibilityClient;
    private final Clock clock;
    private final CartAssessmentService assessmentService;

    @Autowired
    public CartValidationService(
            CurrentActorProvider currentActorProvider,
            CartRepository repository,
            ProductCommerceClient productClient,
            InventoryAvailabilityClient inventoryClient,
            BusinessStoreEligibilityClient eligibilityClient) {
        this(
                currentActorProvider,
                repository,
                productClient,
                inventoryClient,
                eligibilityClient,
                Clock.systemUTC());
    }

    protected CartValidationService(
            CurrentActorProvider currentActorProvider,
            CartRepository repository,
            ProductCommerceClient productClient,
            InventoryAvailabilityClient inventoryClient,
            BusinessStoreEligibilityClient eligibilityClient,
            Clock clock) {
        this.currentActorProvider = currentActorProvider;
        this.repository = repository;
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.eligibilityClient = eligibilityClient;
        this.clock = clock;
        this.assessmentService = new CartAssessmentService(
                productClient,
                inventoryClient,
                eligibilityClient,
                clock);
    }

    // Revalidates one immutable cart read without changing Redis or reserving stock.
    public CartValidationResponse validate() {
        String userId = currentActorProvider.currentActor().subject();
        long started = System.nanoTime();
        try {
            CartDocument cart = repository.get(userId);
            CartValidationResponse response = validate(cart);
            log.info(
                    "Cart validated userId={} cartVersion={} itemCount={} checkoutReady={} issueCounts={} elapsedMs={}",
                    userId,
                    response.cartVersion(),
                    response.itemCount(),
                    response.checkoutReady(),
                    issueCounts(response),
                    elapsedMillis(started));
            return response;
        } catch (CartException exception) {
            log.warn(
                    "Cart validation failed userId={} code={} elapsedMs={}",
                    userId,
                    exception.code(),
                    elapsedMillis(started));
            throw exception;
        }
    }

    private CartValidationResponse validate(CartDocument cart) {
        var assessment = assessmentService.assess(cart);
        List<CartCurrencyTotalResponse> totals = assessment.checkoutReady()
                ? assessmentTotals(assessment.lines())
                : List.of();
        int totalQuantity = cart.items().stream().mapToInt(CartStoredItem::quantity).sum();

        return new CartValidationResponse(
                cart.version(),
                assessment.validatedAt(),
                assessment.checkoutReady(),
                cart.items().size(),
                totalQuantity,
                totals,
                assessment.cartIssues(),
                assessment.lines().stream().map(this::assessmentResponse).toList());
    }

    private CartValidationItemResponse assessmentResponse(
            com.msb.ecom.order_service.model.CartAssessment.Line line) {
        var product = line.product();
        return new CartValidationItemResponse(
                line.stored().listingId(),
                product == null ? "Business item" : product.title(),
                product == null ? null : product.thumbnailUrl(),
                product == null ? null : product.storeName(),
                product == null ? null : product.storeSlug(),
                product == null ? null : product.businessVerified(),
                product == null ? null : product.publicCity(),
                product == null ? null : product.publicRegion(),
                line.stored().quantity(),
                line.availableQuantity(),
                line.stored().observedPrice(),
                product == null ? null : product.priceAmount(),
                normalizedCurrency(line.stored().currency()),
                product == null ? null : normalizedCurrency(product.currency()),
                status(line.issues()),
                line.issues());
    }

    private List<CartCurrencyTotalResponse> assessmentTotals(
            List<com.msb.ecom.order_service.model.CartAssessment.Line> lines) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (var line : lines) {
            totals.merge(
                    normalizedCurrency(line.product().currency()),
                    line.product().priceAmount().multiply(BigDecimal.valueOf(line.stored().quantity())),
                    BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(entry -> new CartCurrencyTotalResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ValidatedLine validateLine(
            CartStoredItem item,
            Map<SellerKey, Optional<BusinessStoreEligibilityClient.Eligibility>> eligibilityCache) {
        ValidatedLine line = new ValidatedLine(item);
        Optional<ProductCommerceClient.ProductContext> currentProduct = productClient.find(item.listingId());
        if (currentProduct.isEmpty()) {
            line.add(issue(
                    LISTING_UNAVAILABLE,
                    "This item is no longer available.",
                    "REMOVE_ITEM"));
            return line;
        }

        line.product = currentProduct.get();
        if (!eligibleProduct(line.product)) {
            line.add(issue(
                    LISTING_UNAVAILABLE,
                    "This item is no longer available.",
                    "REMOVE_ITEM"));
        } else {
            validateSeller(line, eligibilityCache);
        }

        validateInventory(line);
        validateObservedPrice(line);
        return line;
    }

    private void validateSeller(
            ValidatedLine line,
            Map<SellerKey, Optional<BusinessStoreEligibilityClient.Eligibility>> eligibilityCache) {
        SellerKey key = new SellerKey(line.product.businessId(), line.product.storeId());
        Optional<BusinessStoreEligibilityClient.Eligibility> eligibility =
                eligibilityCache.computeIfAbsent(
                        key,
                        ignored -> eligibilityClient.find(key.businessId(), key.storeId()));
        if (eligibility.isEmpty()
                || !key.businessId().equals(eligibility.get().businessId())
                || !key.storeId().equals(eligibility.get().storeId())
                || !eligibility.get().eligible()) {
            line.add(issue(
                    SELLER_UNAVAILABLE,
                    "This seller is not currently available.",
                    "REMOVE_ITEM"));
        }
    }

    private void validateInventory(ValidatedLine line) {
        InventoryAvailabilityClient.Availability availability = inventoryClient.get(line.item.listingId());
        line.availableQuantity = availability.available();
        if (line.product == null
                || !line.item.listingId().equals(availability.listingId())
                || !availability.initialized()
                || availability.businessId() == null) {
            line.add(issue(
                    OUT_OF_STOCK,
                    "This item is currently out of stock.",
                    "REMOVE_ITEM"));
            return;
        }
        if (!line.product.businessId().equals(availability.businessId())) {
            log.error(
                    "Cart validation inventory ownership mismatch listingId={} productBusinessId={} inventoryBusinessId={}",
                    line.item.listingId(),
                    line.product.businessId(),
                    availability.businessId());
            line.add(issue(
                    LISTING_UNAVAILABLE,
                    "This item is no longer available.",
                    "REMOVE_ITEM"));
            return;
        }
        if (availability.available() <= 0) {
            line.add(issue(
                    OUT_OF_STOCK,
                    "This item is currently out of stock.",
                    "REMOVE_ITEM"));
        } else if (availability.available() < line.item.quantity()) {
            line.add(issue(
                    QUANTITY_REDUCED,
                    "Only " + availability.available() + " is currently available.",
                    "SET_AVAILABLE_QUANTITY"));
        }
    }

    private void validateObservedPrice(ValidatedLine line) {
        if (line.product == null || line.product.priceAmount() == null || line.product.currency() == null) {
            return;
        }
        if (line.item.observedPrice().compareTo(line.product.priceAmount()) != 0) {
            line.add(issue(
                    PRICE_CHANGED,
                    "The price changed from "
                            + money(line.item.observedPrice())
                            + " "
                            + normalizedCurrency(line.item.currency())
                            + " to "
                            + money(line.product.priceAmount())
                            + " "
                            + normalizedCurrency(line.product.currency())
                            + ".",
                    "ACCEPT_CURRENT_PRICE"));
        }
        if (!normalizedCurrency(line.item.currency()).equals(normalizedCurrency(line.product.currency()))) {
            line.add(issue(
                    CURRENCY_CHANGED,
                    "The item currency changed. Accept the current price to continue.",
                    "ACCEPT_CURRENT_PRICE"));
        }
    }

    private void applyCurrencyConflict(List<ValidatedLine> lines) {
        Set<String> currencies = new LinkedHashSet<>();
        for (ValidatedLine line : lines) {
            if (line.currentlyPurchasable() && line.product.currency() != null) {
                currencies.add(normalizedCurrency(line.product.currency()));
            }
        }
        if (currencies.size() <= 1) {
            return;
        }
        for (ValidatedLine line : lines) {
            if (line.currentlyPurchasable()) {
                line.add(issue(
                        CURRENCY_CONFLICT,
                        "Your cart contains more than one currency.",
                        "REMOVE_ITEM"));
            }
        }
    }

    private boolean eligibleProduct(ProductCommerceClient.ProductContext product) {
        return "BUSINESS".equals(product.sellerType())
                && "ACTIVE".equals(product.status())
                && product.businessId() != null
                && !product.businessId().isBlank()
                && product.storeId() != null
                && !product.storeId().isBlank()
                && product.priceAmount() != null
                && product.priceAmount().signum() >= 0
                && product.currency() != null
                && !product.currency().isBlank();
    }

    private CartValidationItemResponse response(ValidatedLine line) {
        List<CartValidationIssueResponse> issues = line.issues.values().stream()
                .sorted(Comparator.comparingInt(issue -> ISSUE_PRIORITY.getOrDefault(issue.code(), 99)))
                .toList();
        return new CartValidationItemResponse(
                line.item.listingId(),
                line.product == null ? "Business item" : line.product.title(),
                line.product == null ? null : line.product.thumbnailUrl(),
                line.product == null ? null : line.product.storeName(),
                line.product == null ? null : line.product.storeSlug(),
                line.product == null ? null : line.product.businessVerified(),
                line.product == null ? null : line.product.publicCity(),
                line.product == null ? null : line.product.publicRegion(),
                line.item.quantity(),
                line.availableQuantity,
                line.item.observedPrice(),
                line.product == null ? null : line.product.priceAmount(),
                normalizedCurrency(line.item.currency()),
                line.product == null ? null : normalizedCurrency(line.product.currency()),
                status(issues),
                issues);
    }

    private List<CartCurrencyTotalResponse> totals(List<ValidatedLine> lines) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (ValidatedLine line : lines) {
            totals.merge(
                    normalizedCurrency(line.product.currency()),
                    line.product.priceAmount().multiply(BigDecimal.valueOf(line.item.quantity())),
                    BigDecimal::add);
        }
        return totals.entrySet().stream()
                .map(entry -> new CartCurrencyTotalResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String status(List<CartValidationIssueResponse> issues) {
        if (issues.isEmpty()) {
            return "READY";
        }
        return switch (issues.get(0).code()) {
            case LISTING_UNAVAILABLE -> "LISTING_UNAVAILABLE";
            case SELLER_UNAVAILABLE -> "SELLER_UNAVAILABLE";
            case OUT_OF_STOCK -> "OUT_OF_STOCK";
            case QUANTITY_REDUCED -> "QUANTITY_REDUCED";
            case CURRENCY_CONFLICT -> "CURRENCY_CONFLICT";
            case PRICE_CHANGED, CURRENCY_CHANGED -> "PRICE_CHANGED";
            default -> "LISTING_UNAVAILABLE";
        };
    }

    private CartValidationIssueResponse issue(String code, String message, String action) {
        return new CartValidationIssueResponse(code, message, action);
    }

    private String normalizedCurrency(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }

    private String money(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private Map<String, Long> issueCounts(CartValidationResponse response) {
        Map<String, Long> counts = new LinkedHashMap<>();
        response.cartIssues().forEach(issue -> counts.merge(issue.code(), 1L, Long::sum));
        response.items().stream()
                .flatMap(item -> item.issues().stream())
                .forEach(issue -> counts.merge(issue.code(), 1L, Long::sum));
        return counts;
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private record SellerKey(String businessId, String storeId) {
    }

    private static final class ValidatedLine {

        private final CartStoredItem item;
        private final Map<String, CartValidationIssueResponse> issues = new LinkedHashMap<>();
        private ProductCommerceClient.ProductContext product;
        private Integer availableQuantity;

        private ValidatedLine(CartStoredItem item) {
            this.item = item;
        }

        private void add(CartValidationIssueResponse issue) {
            issues.putIfAbsent(issue.code(), issue);
        }

        private boolean currentlyPurchasable() {
            return product != null
                    && !issues.containsKey(LISTING_UNAVAILABLE)
                    && !issues.containsKey(SELLER_UNAVAILABLE)
                    && !issues.containsKey(OUT_OF_STOCK)
                    && !issues.containsKey(QUANTITY_REDUCED);
        }
    }
}
