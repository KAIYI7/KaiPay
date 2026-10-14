package com.lky.kaipay.security;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Single-JVM admission, never keyed by an account or an unverified forwarded header. */
public final class LoginAdmission {
    private final int peerLimit, totalLimit, concurrentLimit, peerCapacity;
    private final long windowNanos;
    private final LongSupplier ticker;
    private final Map<String, Window> peers = new HashMap<>();
    private Window total;
    private int inFlight;

    public LoginAdmission(int peerLimit, int totalLimit, int concurrentLimit, int peerCapacity, Duration window) {
        this(peerLimit, totalLimit, concurrentLimit, peerCapacity, window, System::nanoTime);
    }

    LoginAdmission(int peerLimit, int totalLimit, int concurrentLimit, int peerCapacity,
                   Duration window, LongSupplier ticker) {
        if (peerLimit < 1 || peerLimit > 10000 || totalLimit < peerLimit || totalLimit > 100000
                || concurrentLimit < 1 || concurrentLimit > 32 || peerCapacity < 1 || peerCapacity > 65536
                || window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid login admission bounds");
        }
        this.peerLimit = peerLimit; this.totalLimit = totalLimit;
        this.concurrentLimit = concurrentLimit; this.peerCapacity = peerCapacity;
        this.windowNanos = window.toNanos(); this.ticker = ticker;
    }

    public synchronized Decision acquire(String remoteAddress) {
        long now = ticker.getAsLong();
        peers.values().removeIf(window -> expired(window, now));
        if (total == null || expired(total, now)) total = new Window(now);
        // A servlet peer address is bounded. Invalid peers fail closed without retaining input.
        if (remoteAddress == null || remoteAddress.isBlank() || remoteAddress.length() > 64)
            return new Decision(null, 1);
        Window peer = peers.get(remoteAddress);
        if (total.attempts >= totalLimit) return new Decision(null, remaining(total, now));
        if (peer != null && peer.attempts >= peerLimit) return new Decision(null, remaining(peer, now));
        if (inFlight >= concurrentLimit) return new Decision(null, 1);
        if (peer == null && peers.size() >= peerCapacity) {
            // Do not evict active budgets: rotating addresses must not reset them.
            long earliest = peers.values().stream().mapToLong(window -> remaining(window, now)).min().orElse(1);
            return new Decision(null, earliest);
        }
        if (peer == null) { peer = new Window(now); peers.put(remoteAddress, peer); }
        peer.attempts++;
        total.attempts++;
        inFlight++;
        return new Decision(new Permit(this), 0);
    }

    private boolean expired(Window window, long now) { return now - window.started >= windowNanos; }
    private long remaining(Window window, long now) {
        return Math.max(1, (windowNanos - (now - window.started) + 999999999L) / 1000000000L);
    }
    private synchronized void release(Permit permit) {
        if (!permit.released) { permit.released = true; inFlight--; }
    }
    // Package-private observability for deterministic resource-bound regressions, no operator reset API.
    synchronized int peerCount() { return peers.size(); }
    private static final class Window {
        private final long started;
        private int attempts;
        private Window(long started) { this.started = started; }
    }
    public record Decision(Permit permit, long retryAfterSeconds) {}
    public static final class Permit implements AutoCloseable {
        private final LoginAdmission owner;
        private boolean released;
        private Permit(LoginAdmission owner) { this.owner = owner; }
        @Override public void close() { owner.release(this); }
    }
}
