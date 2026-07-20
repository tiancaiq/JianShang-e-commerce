package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.dto.CartValidationIssueResponse;
import com.msb.ecom.order_service.model.CartAssessment;
import com.msb.ecom.order_service.model.CartDocument;
import com.msb.ecom.order_service.model.CartStoredItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
public class CartAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(CartAssessmentService.class);

    public static final String LISTING_UNAVAILABLE = "CART_LISTING_UNAVAILABLE";
    public static final String SELLER_UNAVAILABLE = "CART_SELLER_UNAVAILABLE";
    public static final String OUT_OF_STOCK = "CART_OUT_OF_STOCK";
    public static final String QUANTITY_REDUCED = "CART_QUANTITY_REDUCED";
    public static final String CURRENCY_CONFLICT = "CART_CURRENCY_CONFLICT";
    public static final String PRICE_CHANGED = "CART_PRICE_CHANGED";
    public static final String CURRENCY_CHANGED = "CART_CURRENCY_CHANGED";

    private static final Map<String, Integer> ISSUE_PRIORITY = Map.of(
            LISTING_UNAVAILABLE, 0,
            SELLER_UNAVAILABLE, 1,
            OUT_OF_STOCK, 2,
            QUANTITY_REDUCED, 3,
            CURRENCY_CONFLICT, 4,
            PRICE_CHANGED, 5,
            CURRENCY_CHANGED, 5);

    private final ProductCommerceClient productClient;
    private final InventoryAvailabilityClient inventoryClient;
    private final BusinessStoreEligibilityClient eligibilityClient;
    private final Clock clock;

    @Autowired
    public CartAssessmentService(
            ProductCommerceClient productClient,
            InventoryAvailabilityClient inventoryClient,
            BusinessStoreEligibilityClient eligibilityClient) {
        this(productClient, inventoryClient, eligibilityClient, Clock.systemUTC());
    }

    CartAssessmentService(
            ProductCommerceClient productClient,
            InventoryAvailabilityClient inventoryClient,
            BusinessStoreEligibilityClient eligibilityClient,
            Clock clock) {
        this.productClient = productClient;
        this.inventoryClient = inventoryClient;
        this.eligibilityClient = eligibilityClient;
        this.clock = clock;
    }

    // Produces one immutable source assessment shared by cart validation and checkout creation.
    public CartAssessment assess(CartDocument cart) {
        if (cart.items().isEmpty()) {
            return new CartAssessment(
                    cart,
                    clock.instant(),
                    false,
                    List.of(issue("CART_EMPTY", "Your cart is empty.", null)),
                    List.of());
        }

        Map<SellerKey, Optional<BusinessStoreEligibilityClient.Eligibility>> eligibility = new LinkedHashMap<>();
        List<MutableLine> lines = cart.items().stream()
                .map(item -> assessLine(item, eligibility))
                .toList();
        applyCurrencyConflict(lines);
        boolean ready = lines.stream().allMatch(line -> line.issues.isEmpty());
        List<CartValidationIssueResponse> cartIssues = lines.stream()
                .anyMatch(line -> line.issues.containsKey(CURRENCY_CONFLICT))
                ? List.of(issue(
                        CURRENCY_CONFLICT,
                        "Your cart contains more than one currency. Remove items until one currency remains.",
                        "REMOVE_ITEM"))
                : List.of();
        return new CartAssessment(
                cart,
                clock.instant(),
                ready,
                cartIssues,
                lines.stream().map(MutableLine::immutable).toList());
    }

    private MutableLine assessLine(
            CartStoredItem item,
            Map<SellerKey, Optional<BusinessStoreEligibilityClient.Eligibility>> eligibilityCache) {
        MutableLine line = new MutableLine(item);
        Optional<ProductCommerceClient.ProductContext> currentProduct = productClient.find(item.listingId());
        if (currentProduct.isEmpty()) {
            line.add(issue(LISTING_UNAVAILABLE, "This item is no longer available.", "REMOVE_ITEM"));
            return line;
        }

        line.product = currentProduct.get();
        if (!eligibleProduct(line.product)) {
            line.add(issue(LISTING_UNAVAILABLE, "This item is no longer available.", "REMOVE_ITEM"));
        } else {
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

        InventoryAvailabilityClient.Availability availability = inventoryClient.get(item.listingId());
        line.availableQuantity = availability.available();
        if (!availability.initialized() || availability.businessId() == null) {
            line.add(issue(OUT_OF_STOCK, "This item is currently out of stock.", "REMOVE_ITEM"));
        } else if (line.product != null && !line.product.businessId().equals(availability.businessId())) {
            log.error("Cart assessment inventory ownership mismatch listingId={}", item.listingId());
            line.add(issue(LISTING_UNAVAILABLE, "This item is no longer available.", "REMOVE_ITEM"));
        } else if (availability.available() <= 0) {
            line.add(issue(OUT_OF_STOCK, "This item is currently out of stock.", "REMOVE_ITEM"));
        } else if (availability.available() < item.quantity()) {
            line.add(issue(
                    QUANTITY_REDUCED,
                    "Only " + availability.available() + " is currently available.",
                    "SET_AVAILABLE_QUANTITY"));
        }

        if (line.product != null
                && line.product.priceAmount() != null
                && item.observedPrice().compareTo(line.product.priceAmount()) != 0) {
            line.add(issue(
                    PRICE_CHANGED,
                    "The price changed from "
                            + money(item.observedPrice())
                            + " "
                            + currency(item.currency())
                            + " to "
                            + money(line.product.priceAmount())
                            + " "
                            + currency(line.product.currency())
                            + ".",
                    "ACCEPT_CURRENT_PRICE"));
        }
        if (line.product != null
                && !currency(item.currency()).equals(currency(line.product.currency()))) {
            line.add(issue(
                    CURRENCY_CHANGED,
                    "The item currency changed. Accept the current price to continue.",
                    "ACCEPT_CURRENT_PRICE"));
        }
        return line;
    }

    private void applyCurrencyConflict(List<MutableLine> lines) {
        Set<String> currencies = new LinkedHashSet<>();
        lines.stream()
                .filter(MutableLine::currentlyPurchasable)
                .map(line -> currency(line.product.currency()))
                .forEach(currencies::add);
        if (currencies.size() > 1) {
            lines.stream()
                    .filter(MutableLine::currentlyPurchasable)
                    .forEach(line -> line.add(issue(
                            CURRENCY_CONFLICT,
                            "Your cart contains more than one currency.",
                            "REMOVE_ITEM")));
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
                && product.priceAmount().scale() <= 4
                && product.currency() != null
                && product.currency().trim().length() == 3;
    }

    static int issuePriority(CartValidationIssueResponse issue) {
        return ISSUE_PRIORITY.getOrDefault(issue.code(), 99);
    }

    private static CartValidationIssueResponse issue(String code, String message, String action) {
        return new CartValidationIssueResponse(code, message, action);
    }

    private static String currency(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String money(java.math.BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record SellerKey(String businessId, String storeId) {
    }

    private static final class MutableLine {

        private final CartStoredItem item;
        private final Map<String, CartValidationIssueResponse> issues = new LinkedHashMap<>();
        private ProductCommerceClient.ProductContext product;
        private Integer availableQuantity;

        private MutableLine(CartStoredItem item) {
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

        private CartAssessment.Line immutable() {
            return new CartAssessment.Line(
                    item,
                    product,
                    availableQuantity,
                    issues.values().stream()
                            .sorted(Comparator.comparingInt(CartAssessmentService::issuePriority))
                            .toList());
        }
    }
}
