package com.msb.ecom.common.core.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void createsMoneyWithoutImplicitRounding() {
        Money money = Money.of("19.9900", "USD");

        assertEquals(new BigDecimal("19.9900"), money.amount());
        assertEquals("USD", money.currency().getCurrencyCode());
    }

    @Test
    void rejectsPersistenceScaleAboveFour() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of("1.00001", "USD"));
    }
}
