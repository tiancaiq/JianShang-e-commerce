package com.msb.ecom.order_service.service;

import com.msb.ecom.order_service.config.CheckoutProperties;
import com.msb.ecom.order_service.model.CartAssessment;
import com.msb.ecom.order_service.model.CheckoutAggregate;
import com.msb.ecom.order_service.model.CheckoutReleaseStatus;
import com.msb.ecom.order_service.model.CheckoutStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CheckoutCalculationService {

    private static final BigDecimal ZERO = new BigDecimal("0.0000");

    private final CheckoutProperties properties;
    private final PlatformPolicyProvider policyProvider;
    private final ShippingCalculationAdapter shippingAdapter;
    private final TaxCalculationAdapter taxAdapter;
    private final CheckoutUlidGenerator ids;
    private final Clock clock;

    @Autowired
    public CheckoutCalculationService(
            CheckoutProperties properties,
            PlatformPolicyProvider policyProvider,
            ShippingCalculationAdapter shippingAdapter,
            TaxCalculationAdapter taxAdapter,
            CheckoutUlidGenerator ids) {
        this(properties, policyProvider, shippingAdapter, taxAdapter, ids, Clock.systemUTC());
    }

    CheckoutCalculationService(
            CheckoutProperties properties,
            PlatformPolicyProvider policyProvider,
            ShippingCalculationAdapter shippingAdapter,
            TaxCalculationAdapter taxAdapter,
            CheckoutUlidGenerator ids,
            Clock clock) {
        this.properties = properties;
        this.policyProvider = policyProvider;
        this.shippingAdapter = shippingAdapter;
        this.taxAdapter = taxAdapter;
        this.ids = ids;
        this.clock = clock;
    }

    // Builds all immutable snapshots and verifies exact local-demo allocation equations.
    public CheckoutAggregate calculate(
            String checkoutId,
            String buyerId,
            CartAssessment assessment,
            BuyerIdentityClient.BuyerAddress address) {
        Instant now = clock.instant();
        String currency = assessment.lines().stream()
                .map(line -> normalizedCurrency(line.product().currency()))
                .distinct()
                .reduce((left, right) -> {
                    throw new IllegalArgumentException("Checkout cart must use one currency.");
                })
                .orElseThrow(() -> new IllegalArgumentException("Checkout cart is empty."));
        PlatformPolicyProvider.Policy sourcePolicy = policyProvider.current();

        List<BaseLine> baseLines = assessment.lines().stream()
                .sorted(Comparator.comparing(line -> line.stored().listingId()))
                .map(line -> {
                    BigDecimal unit = money(line.product().priceAmount());
                    BigDecimal subtotal = money(unit.multiply(BigDecimal.valueOf(line.stored().quantity())));
                    return new BaseLine(line, unit, subtotal);
                })
                .toList();

        ShippingCalculationAdapter.Result shippingResult = shippingAdapter.calculate(
                currency,
                baseLines.stream()
                        .map(line -> new ShippingCalculationAdapter.Line(
                                line.line().stored().listingId(),
                                line.line().product().businessId(),
                                line.line().product().storeId(),
                                line.subtotal()))
                        .toList());
        validateShipping(baseLines, shippingResult);
        Map<String, BigDecimal> shippingByListing = new LinkedHashMap<>();
        shippingResult.quotes().forEach(quote -> shippingByListing.putAll(quote.allocations()));

        TaxCalculationAdapter.Result taxResult = taxAdapter.calculate(
                currency,
                baseLines.stream()
                        .map(line -> new TaxCalculationAdapter.Line(
                                line.line().stored().listingId(),
                                line.subtotal(),
                                shippingByListing.get(line.line().stored().listingId())))
                        .toList(),
                address);
        validateTax(baseLines, taxResult);

        Map<Group, CheckoutAggregate.Policy> policies = new LinkedHashMap<>();
        for (BaseLine line : baseLines) {
            Group group = new Group(line.line().product().businessId(), line.line().product().storeId());
            policies.computeIfAbsent(group, ignored -> new CheckoutAggregate.Policy(
                    ids.next(),
                    group.businessId(),
                    group.storeId(),
                    sourcePolicy.id(),
                    sourcePolicy.source(),
                    sourcePolicy.version(),
                    sourcePolicy.shippingText(),
                    sourcePolicy.cancellationText(),
                    sourcePolicy.returnText()));
        }

        List<CheckoutAggregate.Item> items = new ArrayList<>();
        int lineNumber = 1;
        for (BaseLine base : baseLines) {
            var line = base.line();
            BigDecimal shipping = money(shippingByListing.get(line.stored().listingId()));
            BigDecimal tax = money(taxResult.allocations().get(line.stored().listingId()));
            BigDecimal total = money(base.subtotal().add(shipping).add(tax));
            Group group = new Group(line.product().businessId(), line.product().storeId());
            items.add(new CheckoutAggregate.Item(
                    ids.next(),
                    lineNumber++,
                    line.stored().listingId(),
                    line.product().businessId(),
                    line.product().storeId(),
                    line.product().version(),
                    line.product().title(),
                    line.product().sku(),
                    line.product().condition() == null ? "UNSPECIFIED" : line.product().condition(),
                    line.product().thumbnailUrl(),
                    line.stored().quantity(),
                    base.unitPrice(),
                    currency,
                    base.subtotal(),
                    shipping,
                    tax,
                    ZERO,
                    total,
                    policies.get(group).id(),
                    sourcePolicy.version()));
        }

        List<CheckoutAggregate.ShippingQuote> shippingQuotes = shippingResult.quotes().stream()
                .map(quote -> new CheckoutAggregate.ShippingQuote(
                        ids.next(),
                        quote.businessId(),
                        quote.storeId(),
                        quote.methodCode(),
                        money(quote.amount()),
                        currency,
                        shippingResult.adapter(),
                        now))
                .toList();
        BigDecimal subtotal = sum(items.stream().map(CheckoutAggregate.Item::lineSubtotal).toList());
        BigDecimal shipping = sum(items.stream().map(CheckoutAggregate.Item::shippingAllocation).toList());
        BigDecimal tax = sum(items.stream().map(CheckoutAggregate.Item::taxAllocation).toList());
        BigDecimal total = money(subtotal.add(shipping).add(tax));

        return new CheckoutAggregate(
                checkoutId,
                buyerId,
                CheckoutStatus.RESERVING,
                0,
                assessment.cart().version(),
                cartHash(baseLines),
                currency,
                subtotal,
                shipping,
                tax,
                ZERO,
                total,
                now.plus(properties.sessionLifetime()),
                null,
                null,
                null,
                CheckoutReleaseStatus.NOT_REQUIRED,
                null,
                now,
                now,
                new CheckoutAggregate.Address(
                        address.id(),
                        address.version(),
                        address.label(),
                        address.recipientName(),
                        address.phone(),
                        address.line1(),
                        address.line2(),
                        address.city(),
                        address.region(),
                        address.postalCode(),
                        address.countryCode()),
                List.copyOf(items),
                shippingQuotes,
                new CheckoutAggregate.TaxQuote(
                        ids.next(),
                        money(taxResult.amount()),
                        currency,
                        taxResult.adapter(),
                        now),
                List.copyOf(policies.values()));
    }

    private void validateShipping(
            List<BaseLine> lines,
            ShippingCalculationAdapter.Result result) {
        if (result == null || !properties.shippingAdapter().equals(result.adapter())) {
            throw new IllegalArgumentException("Shipping adapter result is invalid.");
        }
        Map<String, BigDecimal> allocations = new LinkedHashMap<>();
        BigDecimal quoteTotal = ZERO;
        for (ShippingCalculationAdapter.Quote quote : result.quotes()) {
            quoteTotal = quoteTotal.add(money(quote.amount()));
            quote.allocations().forEach((listingId, amount) -> {
                if (allocations.putIfAbsent(listingId, money(amount)) != null) {
                    throw new IllegalArgumentException("Shipping allocation is duplicated.");
                }
            });
        }
        requireExactLines(lines, allocations, "Shipping");
        if (sum(new ArrayList<>(allocations.values())).compareTo(money(quoteTotal)) != 0) {
            throw new IllegalArgumentException("Shipping allocations do not equal quotes.");
        }
    }

    private void validateTax(List<BaseLine> lines, TaxCalculationAdapter.Result result) {
        if (result == null || !properties.taxAdapter().equals(result.adapter())) {
            throw new IllegalArgumentException("Tax adapter result is invalid.");
        }
        requireExactLines(lines, result.allocations(), "Tax");
        if (sum(new ArrayList<>(result.allocations().values())).compareTo(money(result.amount())) != 0) {
            throw new IllegalArgumentException("Tax allocations do not equal quote.");
        }
    }

    private void requireExactLines(
            List<BaseLine> lines,
            Map<String, BigDecimal> allocations,
            String name) {
        List<String> expected = lines.stream().map(line -> line.line().stored().listingId()).sorted().toList();
        List<String> actual = allocations.keySet().stream().sorted().toList();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(name + " allocations do not match checkout lines.");
        }
        allocations.values().forEach(this::money);
    }

    private BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 4 || value.precision() > 19) {
            throw new IllegalArgumentException("Checkout amount is invalid.");
        }
        return value.setScale(4);
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return money(values.stream().reduce(ZERO, BigDecimal::add));
    }

    private String cartHash(List<BaseLine> lines) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (BaseLine line : lines) {
                update(digest, line.line().stored().listingId());
                update(digest, Integer.toString(line.line().stored().quantity()));
                update(digest, line.unitPrice().toPlainString());
                update(digest, normalizedCurrency(line.line().product().currency()));
                update(digest, Long.toString(line.line().product().version()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String normalizedCurrency(String currency) {
        String normalized = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() != 3) {
            throw new IllegalArgumentException("Checkout currency is invalid.");
        }
        return normalized;
    }

    private record BaseLine(
            CartAssessment.Line line,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {
    }

    private record Group(String businessId, String storeId) {
    }
}
