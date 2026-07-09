package com.msb.ecom.chat_service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;

@Component
public class UlidGenerator {

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        return encodeTime(Instant.now().toEpochMilli()) + encodeRandom(randomness);
    }

    private String encodeTime(long timeMillis) {
        char[] chars = new char[10];
        long value = timeMillis;
        for (int i = 9; i >= 0; i--) {
            chars[i] = CROCKFORD[(int) (value & 31)];
            value >>>= 5;
        }
        return new String(chars);
    }

    private String encodeRandom(byte[] bytes) {
        char[] chars = new char[16];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5 && index < chars.length) {
                bits -= 5;
                chars[index++] = CROCKFORD[(buffer >>> bits) & 31];
            }
        }
        if (index < chars.length) {
            chars[index] = CROCKFORD[(buffer << (5 - bits)) & 31];
        }
        return new String(chars);
    }
}
