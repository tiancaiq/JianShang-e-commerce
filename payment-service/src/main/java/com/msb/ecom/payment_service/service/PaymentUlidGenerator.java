package com.msb.ecom.payment_service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;

@Component
public class PaymentUlidGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random;
    private final Clock clock;

    public PaymentUlidGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    PaymentUlidGenerator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public String next() {
        long timestamp = clock.instant().toEpochMilli();
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
