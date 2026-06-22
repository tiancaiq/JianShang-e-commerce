package com.msb.ecom.auth_service.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;

@Component
public class UlidGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String next() {
        byte[] entropy = new byte[10];
        random.nextBytes(entropy);

        char[] chars = new char[26];
        writeTime(chars, Instant.now().toEpochMilli());
        writeEntropy(chars, entropy);
        return new String(chars);
    }

    private void writeTime(char[] chars, long timestamp) {
        for (int i = 9; i >= 0; i--) {
            chars[i] = ENCODING[(int) (timestamp & 0x1F)];
            timestamp >>>= 5;
        }
    }

    private void writeEntropy(char[] chars, byte[] entropy) {
        int bitBuffer = 0;
        int bitCount = 0;
        int index = 10;

        for (byte value : entropy) {
            bitBuffer = (bitBuffer << 8) | (value & 0xFF);
            bitCount += 8;
            while (bitCount >= 5 && index < chars.length) {
                bitCount -= 5;
                chars[index++] = ENCODING[(bitBuffer >>> bitCount) & 0x1F];
            }
        }
    }
}
