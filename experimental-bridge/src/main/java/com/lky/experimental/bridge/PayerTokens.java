package com.lky.experimental.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Request-scoped read capabilities. A process restart revokes all links, without changing snapshots. */
final class PayerTokens {
    private final byte[] key = new byte[32];

    PayerTokens() { new SecureRandom().nextBytes(key); }

    String issue(UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(("experimental-checkout-read-v1:" + id).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException ignored) {
            throw new IllegalStateException("Checkout access unavailable");
        }
    }

    boolean authorized(UUID id, String header) {
        if (header == null || !header.matches("Bearer [A-Za-z0-9_-]{43}")) return false;
        return MessageDigest.isEqual(issue(id).getBytes(StandardCharsets.US_ASCII),
                header.substring(7).getBytes(StandardCharsets.US_ASCII));
    }
}
