package com.filevault.share;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class ShareToken {
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareToken() { }

    static String newToken() {
        byte[] buffer = new byte[32];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    static boolean validFormat(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 provider unavailable.", failure);
        }
    }
}
