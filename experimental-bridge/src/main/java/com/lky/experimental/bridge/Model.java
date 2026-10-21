package com.lky.experimental.bridge;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Model {
    private Model() {}
    public enum Status { AWAITING_PAYMENT, PAYMENT_DETECTED, AWAITING_VERIFICATION, MANUALLY_CONFIRMED, AMBIGUOUS, EXPIRED, REVIEW_EXPIRED }
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
    public enum PayerEvidence { NONE, SYNTHETIC, NOTIFICATION_UNVERIFIED }
    // Explicit allowlist: payer access never serializes request observation IDs or candidate metadata.
    public record Checkout(UUID id, int amountMinor, Instant createdAt, Instant expiresAt,
                           Status status, PayerEvidence evidence, boolean paymentWindowClosed,
                           Instant reviewExpiresAt, boolean reviewAvailable) {}
    public record PayerLink(UUID requestId, String token) {}
    public record Confirmation(Boolean historyChecked, UUID expectedEventId) {
        public Confirmation(Boolean historyChecked) { this(historyChecked, null); }
    }
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
    public record ReviewGrant(UUID eventId, Instant reviewExpiresAt, boolean revoked) {}
    public record PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt,
                                 Status status, UUID observationId, Integer lifecycleVersion,
                                 boolean paymentClosed, ReviewGrant reviewGrant) {
        public PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt, Status status, UUID observationId) {
            this(id, amountMinor, createdAt, expiresAt, status, observationId, null, false, null);
        }
        PaymentRequest withStatus(Status next, UUID observation) {
            return new PaymentRequest(id, amountMinor, createdAt, expiresAt, next, observation, lifecycleVersion, paymentClosed, reviewGrant);
        }
    }
    public record Observation(Candidate candidate, Disposition disposition, UUID requestId, Instant acceptedAt) {
        public Observation(Candidate candidate, Disposition disposition, UUID requestId) { this(candidate, disposition, requestId, null); }
        Observation withDisposition(Disposition next) { return new Observation(candidate, next, requestId, acceptedAt); }
    }
    public record Snapshot(List<PaymentRequest> requests, List<Observation> observations) {}
}
