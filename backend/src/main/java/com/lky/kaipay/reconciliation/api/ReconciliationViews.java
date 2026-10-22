package com.lky.kaipay.reconciliation.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Explicit browser contract. Source free text and transport payloads are never returned. */
public final class ReconciliationViews {
    private ReconciliationViews() {}
    public record RunView(UUID runId, UUID merchantId, String assessmentScope, String ruleset,
                          int schemaVersion, String buildRevision, Instant fromInclusive,
                          Instant cutoffExclusive, int graceSeconds, int paymentLimit,
                          int executionLimitSeconds, Instant snapshotObservedAt, Instant completedAt,
                          int cohortCount, Map<String, Integer> counts, String status) {}
    public record ObservationView(UUID paymentId, String primaryClass, String severity,
                                  java.util.List<String> reasonCodes, JsonNode evidence, String explanation) {}
}
