package com.msb.ecom.order_service.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;

@Component
public class CheckoutUlidGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    @Autowired
    public CheckoutUlidGenerator() {
        this(Clock.systemUTC());
    }

    CheckoutUlidGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        long timestamp = clock.millis();
        char[] value = new char[26];
        for (int index = 9; index >= 0; index--) {
            value[index] = ENCODING[(int) (timestamp & 31)];
            timestamp >>>= 5;
        }
        for (int index = 10; index < value.length; index++) {
            value[index] = ENCODING[random.nextInt(ENCODING.length)];
        }
        return new String(value);
    }
}
