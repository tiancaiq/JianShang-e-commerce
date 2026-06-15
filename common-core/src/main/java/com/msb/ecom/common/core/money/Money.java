package com.msb.ecom.common.core.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Transport-neutral money value. Domain-specific sign and rounding rules stay
 * in the owning service.
 */
public record Money(BigDecimal amount, Currency currency) {

    public static final int MAX_SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.scale() > MAX_SCALE) {
            throw new IllegalArgumentException("amount scale must not exceed " + MAX_SCALE);
        }
    }

    public static Money of(String amount, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode");
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }
}
