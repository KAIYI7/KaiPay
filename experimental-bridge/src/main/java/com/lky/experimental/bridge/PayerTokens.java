package com.lky.experimental.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Request-scoped read capabilities. A process restart revokes all links, without changing snapshots. */
final class PayerTokens {
    static final Duration LIFETIME = Duration.ofMinutes(15);
    static final int MAX_GRANTS = 500;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final LongSupplier elapsedNanos;
    private final Map<UUID, Grant> grants = new HashMap<>();

    PayerTokens() { this(Clock.systemUTC(), System::nanoTime); }
    PayerTokens(Clock clock) { this(clock, System::nanoTime); }
    PayerTokens(Clock clock, LongSupplier elapsedNanos) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.elapsedNanos = java.util.Objects.requireNonNull(elapsedNanos);
    }

    synchronized String issue(UUID id) {
        java.util.Objects.requireNonNull(id);
        Instant now = clock.instant();
        long elapsed = elapsedNanos.getAsLong();
        grants.values().removeIf(grant -> expired(grant, now, elapsed));
        if (!grants.containsKey(id) && grants.size() >= MAX_GRANTS)
            throw new BridgeFailure(409, "PAYER_ACCESS_CAPACITY");
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        grants.put(id, new Grant(token.getBytes(StandardCharsets.US_ASCII), now.plus(LIFETIME), elapsed));
        return token;
    }

    synchronized boolean authorized(UUID id, String header) {
        if (header == null || !header.matches("Bearer [A-Za-z0-9_-]{43}")) return false;
        Grant grant = grants.get(id);
        if (grant == null) return false;
        if (expired(grant, clock.instant(), elapsedNanos.getAsLong())) {
            grants.remove(id); // Observed expiry cannot be undone by a later wall-clock rollback.
            return false;
        }
        return MessageDigest.isEqual(grant.token(), header.substring(7).getBytes(StandardCharsets.US_ASCII));
    }

    synchronized void revoke(UUID id) { grants.remove(id); }

    private boolean expired(Grant grant, Instant now, long elapsed) {
        return !now.isBefore(grant.expiresAt()) || elapsed - grant.issuedNanos() >= LIFETIME.toNanos();
    }

    private record Grant(byte[] token, Instant expiresAt, long issuedNanos) {}
}
