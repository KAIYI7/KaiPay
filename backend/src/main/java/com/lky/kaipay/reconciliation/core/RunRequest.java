package com.lky.kaipay.reconciliation.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Caller identity is independent of runtime clocks, build and diagnostic consumer configuration. */
public record RunRequest(UUID runId, UUID merchantId, String scope, String ruleset,
                         Instant fromInclusive, Instant cutoffExclusive, String actor, String reason) {
    public static final String SCOPE = "AUTHORIZATION_V1";
    public static final String RULESET = "AUTHORIZATION_V1.0";
    public RunRequest {
        Objects.requireNonNull(runId); Objects.requireNonNull(merchantId);
        Objects.requireNonNull(scope); Objects.requireNonNull(ruleset);
        Objects.requireNonNull(fromInclusive); Objects.requireNonNull(cutoffExclusive);
        if (scope.length() > 40 || ruleset.length() > 40 || actor == null || !actor.matches("[A-Za-z0-9_-]{1,100}")
                || !Set.of("DEMO_REVIEW","INCIDENT_REVIEW","VERIFICATION").contains(reason)
                || fromInclusive.getNano() % 1000 != 0 || cutoffExclusive.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("Invalid reconciliation input");
        }
    }
    public void validateNew() {
        if (!SCOPE.equals(scope) || !RULESET.equals(ruleset) || !fromInclusive.isBefore(cutoffExclusive)
                || java.time.Duration.between(fromInclusive, cutoffExclusive).compareTo(java.time.Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("Unsupported reconciliation contract or interval");
    }
    public String fingerprint() {
        try {
            var data = new LinkedHashMap<String,Object>();
            data.put("scope",scope); data.put("ruleset",ruleset); data.put("merchantId",merchantId.toString());
            var format = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
            data.put("fromInclusive",format.format(fromInclusive)); data.put("cutoffExclusive",format.format(cutoffExclusive));
            data.put("actor",actor); data.put("reason",reason);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsString(data).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint reconciliation input"); }
    }
}
