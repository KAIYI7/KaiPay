package com.lky.experimental.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
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
    /** Persisted on the one checkout of an observed-evidence store; absent (null, never serialized) everywhere else. */
    public enum EvidenceMode { OBSERVED_NOTIFICATION }
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
    // revision counts persisted changes (0 for snapshots written before it existed); integrationReference binds an
    // integration client's canonical reference to exactly this request and is null for receiver-created requests.
    // evidenceMode is omitted while null, so synthetic and non-integration snapshots and responses keep their bytes,
    // and an older binary (fail-on-unknown-properties) refuses only an observed-mode snapshot.
    public record PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt,
                                 Status status, UUID observationId, Integer lifecycleVersion,
                                 boolean paymentClosed, ReviewGrant reviewGrant, long revision, UUID integrationReference,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) EvidenceMode evidenceMode) {
        public PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt, Status status, UUID observationId,
                              Integer lifecycleVersion, boolean paymentClosed, ReviewGrant reviewGrant, long revision, UUID integrationReference) {
            this(id, amountMinor, createdAt, expiresAt, status, observationId, lifecycleVersion, paymentClosed, reviewGrant, revision,
                    integrationReference, null);
        }
        public PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt, Status status, UUID observationId) {
            this(id, amountMinor, createdAt, expiresAt, status, observationId, null, false, null);
        }
        public PaymentRequest(UUID id, int amountMinor, Instant createdAt, Instant expiresAt, Status status, UUID observationId,
                              Integer lifecycleVersion, boolean paymentClosed, ReviewGrant reviewGrant) {
            this(id, amountMinor, createdAt, expiresAt, status, observationId, lifecycleVersion, paymentClosed, reviewGrant, 0, null);
        }
        PaymentRequest withStatus(Status next, UUID observation) { return changed(next, observation, paymentClosed, reviewGrant); }
        /** The only path for later changes: a real change increments revision, an identical one returns this request. */
        PaymentRequest changed(Status next, UUID observation, boolean closed, ReviewGrant grant) {
            if (next == status && java.util.Objects.equals(observation, observationId) && closed == paymentClosed
                    && java.util.Objects.equals(grant, reviewGrant)) return this;
            return new PaymentRequest(id, amountMinor, createdAt, expiresAt, next, observation, lifecycleVersion, closed, grant,
                    revision + 1, integrationReference, evidenceMode);
        }
    }
    public record IntegrationCreate(Integer amountMinor, com.fasterxml.jackson.databind.JsonNode currency) {}
    // Explicit allowlist for the integration credential: no observation, grant, candidate or payer material.
    // evidenceMode is the observed-mode-only key; omitted while null, so synthetic bodies keep their exact bytes.
    public record IntegrationCheckout(UUID reference, UUID checkoutId, int amountMinor, Status status, PayerEvidence evidence,
                                      Instant createdAt, Instant expiresAt, Instant reviewExpiresAt, long revision,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) EvidenceMode evidenceMode) {
        public IntegrationCheckout(UUID reference, UUID checkoutId, int amountMinor, Status status, PayerEvidence evidence,
                                   Instant createdAt, Instant expiresAt, Instant reviewExpiresAt, long revision) {
            this(reference, checkoutId, amountMinor, status, evidence, createdAt, expiresAt, reviewExpiresAt, revision, null);
        }
    }
    public record Observation(Candidate candidate, Disposition disposition, UUID requestId, Instant acceptedAt) {
        public Observation(Candidate candidate, Disposition disposition, UUID requestId) { this(candidate, disposition, requestId, null); }
        Observation withDisposition(Disposition next) { return new Observation(candidate, next, requestId, acceptedAt); }
    }
    public record Snapshot(List<PaymentRequest> requests, List<Observation> observations) {}
}
