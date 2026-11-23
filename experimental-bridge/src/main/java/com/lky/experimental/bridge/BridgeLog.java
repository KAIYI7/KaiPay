package com.lky.experimental.bridge;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * Text-free operator lines on standard output. Nothing here ever prints client-supplied text, an exception or Jackson
 * message, a path, header, body, event ID, amount or credential: a rejection prints only a code from the compiled
 * allowlist below, and any other code prints as {@code UNLISTED}.
 */
final class BridgeLog {
    /** Every code the live-device POST routes can return: the service's BridgeFailure codes and the filter's own. */
    static final Set<String> LIVE_CODES = Set.of(
            "UNAUTHORIZED", "LIVE_DELIVERY_DISABLED", "JSON_REQUIRED", "BODY_TOO_LARGE", "INVALID_REQUEST", "TLS_REQUIRED",
            "EVENT_INVALID", "EVENT_TIME_INVALID", "PROFILE_UNSUPPORTED", "AMOUNT_INVALID", "DEVICE_SCOPE_INVALID",
            "GENUINE_DISABLED", "SYNTHETIC_DISABLED", "EVENT_ID_REUSED", "NOTIFICATION_ORDER_INVALID", "OBSERVATION_CAPACITY",
            "STORE_UNAVAILABLE", "INVALIDATION_INVALID", "OBSERVATION_NOT_FOUND");
    /** At most this many rejection lines per window; later ones in the window are only counted. */
    static final int LIMIT = 20, WINDOW_SECONDS = 60;
    private final PrintStream out;
    private final Clock clock;
    private Instant window;
    private int printed, suppressed;

    BridgeLog(PrintStream out, Clock clock) { this.out = out; this.clock = clock; }

    synchronized void line(String text) { out.println(text); out.flush(); }

    /** Prints the count suppressed so far, if any: at the next rejection after the window, and when the bridge stops. */
    synchronized void flush() {
        if (suppressed > 0) line("LIVE_REJECTED_SUPPRESSED count=" + suppressed);
        suppressed = 0;
    }

    synchronized void liveRejected(String code) {
        Instant now = clock.instant();
        if (window == null || !now.isBefore(window.plusSeconds(WINDOW_SECONDS))) { flush(); window = now; printed = 0; }
        if (printed >= LIMIT) { suppressed++; return; }
        printed++;
        line("LIVE_REJECTED code=" + (code != null && LIVE_CODES.contains(code) ? code : "UNLISTED"));
    }
}
