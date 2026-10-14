package com.lky.experimental.bridge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Model {
    private Model() {}
    public enum Status { AWAITING_PAYMENT, PAYMENT_DETECTED, AWAITING_VERIFICATION, MANUALLY_CONFIRMED, AMBIGUOUS, EXPIRED }
    public enum Disposition { MATCHED, AMBIGUOUS, UNMATCHED, EXPIRED, UPDATE_IGNORED, DELAYED, INVALIDATED }
    public enum Source {
        SIMULATED, TNG_NOTIFICATION_CANDIDATE, SYNTHETIC;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
        public static Source parse(Object value) {
            if (!(value instanceof String text)) throw new IllegalArgumentException("SOURCE_INVALID");
            try { return valueOf(text); } catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("SOURCE_INVALID"); }
        }
    }
    public record CreateRequest(Integer amountMinor) {}
    public record Confirmation(Boolean historyChecked) {}
    public record Resolution(UUID requestId, Boolean historyChecked) {}
    public record Invalidation(String reason) {}
    // A separate wire type keeps legacy simulation parsing unchanged and requires textual UTC timestamps.
    public record NotificationInput(UUID eventId, UUID notificationId, Integer sequence, com.fasterxml.jackson.databind.JsonNode observedAt,
                                    Integer amountMinor, Source source, UUID deviceId, String classification,
                                    String classifierProfile, String evidenceQuality) {
        Candidate candidate() {
            try {
                if (observedAt == null || !observedAt.isTextual() || !observedAt.textValue().endsWith("Z")) throw new IllegalArgumentException();
                return new Candidate(eventId, notificationId, sequence, Instant.parse(observedAt.textValue()), amountMinor,
                        source, deviceId, classification, classifierProfile, evidenceQuality);
            } catch (Exception ignored) { throw new BridgeFailure(422, "EVENT_TIME_INVALID"); }
        }
    }
    // The API deliberately has no notification text, sender, wallet, QR, or credential fields.
    public record Candidate(UUID eventId, UUID notificationId, Integer sequence, Instant observedAt,
                            Integer amountMinor, Source source, UUID deviceId, String classification,
                            String classifierProfile, String evidenceQuality) {
        public Candidate(UUID eventId, UUID notificationId, Integer sequence, Instant observedAt, Integer amountMinor, Source source) {
            this(eventId, notificationId, sequence, observedAt, amountMinor, source, null, null, null, null);
        }
    }
    public record PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt,
                                 Status status, UUID observationId) {
        PaymentRequest withStatus(Status next, UUID observation) {
            return new PaymentRequest(id, amountMinor, createdAt, expiresAt, next, observation);
        }
    }
    public record Observation(Candidate candidate, Disposition disposition, UUID requestId) {}
    public record Snapshot(List<PaymentRequest> requests, List<Observation> observations) {}
}
