package com.lky.experimental.bridge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Model {
    private Model() {}
    public enum Status { AWAITING_PAYMENT, PAYMENT_DETECTED, AWAITING_VERIFICATION, MANUALLY_CONFIRMED, AMBIGUOUS, EXPIRED }
    public enum Disposition { MATCHED, AMBIGUOUS, UNMATCHED, EXPIRED, UPDATE_IGNORED }
    public enum Source {
        SIMULATED;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Source parse(Object value) {
            if (!(value instanceof String text) || !"SIMULATED".equals(text)) throw new IllegalArgumentException("SOURCE_INVALID");
            return SIMULATED;
        }
    }
    public record CreateRequest(Integer amountMinor) {}
    public record Confirmation(Boolean historyChecked) {}
    // The API deliberately has no notification text, sender, wallet, QR, or credential fields.
    public record Candidate(UUID eventId, UUID notificationId, Integer sequence, Instant observedAt,
                            Integer amountMinor, Source source) {}
    public record PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt,
                                 Status status, UUID observationId) {
        PaymentRequest withStatus(Status next, UUID observation) {
            return new PaymentRequest(id, amountMinor, createdAt, expiresAt, next, observation);
        }
    }
    public record Observation(Candidate candidate, Disposition disposition, UUID requestId) {}
    public record Snapshot(List<PaymentRequest> requests, List<Observation> observations) {}
}
