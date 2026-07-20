package com.msb.ecom.inventory_service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;

@Component
public class UlidGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String next() {
        long timestamp = Instant.now().toEpochMilli();
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
