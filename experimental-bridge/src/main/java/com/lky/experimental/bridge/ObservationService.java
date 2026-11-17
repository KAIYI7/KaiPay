package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Single-process serialized decisions, persisted before visible success. */
final class ObservationService {
    static final int MAX_REQUESTS = 500, MAX_OBSERVATIONS = 1000, MAX_AMOUNT = 1_000_000;
    private final SnapshotStore store;
    private final Clock clock;
    private final UUID deviceId;
    private final int reviewPeriodSeconds;
    // Integration mode: no genuine candidate is admitted, so none can match, compete with or disqualify any checkout.
    private final boolean syntheticOnly;
    private Snapshot state;

    ObservationService(SnapshotStore store, Clock clock) { this(store, clock, null); }
    ObservationService(SnapshotStore store, Clock clock, UUID deviceId) { this(store, clock, deviceId, 1800); }
    ObservationService(SnapshotStore store, Clock clock, UUID deviceId, int reviewPeriodSeconds) {
        this(store, clock, deviceId, reviewPeriodSeconds, false);
    }
    ObservationService(SnapshotStore store, Clock clock, UUID deviceId, int reviewPeriodSeconds, boolean syntheticOnly) {
        if (reviewPeriodSeconds < 300 || reviewPeriodSeconds > 1800)
            throw new IllegalStateException("Review period must be between 300 and 1800 seconds");
        this.store = store; this.clock = clock; this.deviceId = deviceId; this.reviewPeriodSeconds = reviewPeriodSeconds;
        this.syntheticOnly = syntheticOnly;
        state = store.load();
        try { validateStored(); }
        catch (Exception ignored) { throw new IllegalStateException("Invalid experimental store"); }
        // Bindings outlive the process mode. Only integration mode keeps genuine candidates away from a bound
        // checkout, so an open one must never be served by a process that admits genuine evidence.
        Instant now = clock.instant();
        if (!syntheticOnly && state.requests().stream().anyMatch(r -> r.integrationReference() != null && !terminal(r) && held(r, now)))
            throw new IllegalStateException("Open integration checkouts require integration mode");
        state = new Snapshot(List.copyOf(state.requests()), List.copyOf(state.observations()));
    }

    private void validateStored() {
        if (state.requests() == null || state.observations() == null || state.requests().size() > MAX_REQUESTS
                || state.observations().size() > MAX_OBSERVATIONS) throw new IllegalArgumentException();
        for (Observation o : state.observations()) {
            validate(o.candidate(), o.candidate().source() != Source.SIMULATED);
            if (o.disposition() == null) throw new IllegalArgumentException();
        }
        if (state.requests().stream().map(PaymentRequest::id).distinct().count() != state.requests().size()
                || state.observations().stream().map(o -> o.candidate().eventId()).distinct().count() != state.observations().size())
            throw new IllegalArgumentException();
        List<UUID> references = state.requests().stream().map(PaymentRequest::integrationReference).filter(Objects::nonNull).toList();
        if (references.stream().distinct().count() != references.size()) throw new IllegalArgumentException();
        for (PaymentRequest r : state.requests()) {
            amount(r.amountMinor());
            if (r.revision() < 0 || r.integrationReference() != null && (!v2(r) || r.revision() < 1
                    || evidence(r) == PayerEvidence.NOTIFICATION_UNVERIFIED)) throw new IllegalArgumentException();
            if (r.id() == null || r.createdAt() == null || r.expiresAt() == null || r.status() == null
                    || !r.expiresAt().equals(r.createdAt().plusSeconds(600))) throw new IllegalArgumentException();
            if (!v2(r)) {
                if (r.lifecycleVersion() != null && r.lifecycleVersion() != 1 || r.reviewGrant() != null
                        || r.status() == Status.REVIEW_EXPIRED) throw new IllegalArgumentException();
                continue;
            }
            ReviewGrant grant = r.reviewGrant();
            if (r.status() == Status.REVIEW_EXPIRED && (grant == null || !r.paymentClosed())
                    || r.status() == Status.EXPIRED && grant != null
                    || r.status() == Status.EXPIRED && !r.paymentClosed()
                    || r.paymentClosed() && r.status() == Status.AWAITING_PAYMENT) throw new IllegalArgumentException();
            if (grant != null) {
                Observation original = observation(grant.eventId());
                long period = java.time.Duration.between(r.expiresAt(), grant.reviewExpiresAt()).getSeconds();
                if (grant.eventId() == null || period < 300 || period > 1800
                        || !grant.reviewExpiresAt().equals(r.expiresAt().plusSeconds(period))
                        || original == null || original.acceptedAt() == null || !serverWindow(r, original.acceptedAt())
                        || !freshAtAdmission(original)
                        || !targetAmountTime(r, original.candidate()) || !r.id().equals(original.requestId())) throw new IllegalArgumentException();
                if (grant.revoked()) {
                    if (r.observationId() != null || r.status() != Status.AMBIGUOUS && r.status() != Status.REVIEW_EXPIRED
                            || original.disposition() != Disposition.AMBIGUOUS && original.disposition() != Disposition.INVALIDATED)
                        throw new IllegalArgumentException();
                } else if (!grant.eventId().equals(r.observationId()) || r.status() != Status.PAYMENT_DETECTED
                        && r.status() != Status.AWAITING_VERIFICATION && r.status() != Status.MANUALLY_CONFIRMED
                        && r.status() != Status.REVIEW_EXPIRED) throw new IllegalArgumentException();
            }
            if (r.status() == Status.AWAITING_PAYMENT || r.status() == Status.AMBIGUOUS || r.status() == Status.EXPIRED) {
                if (r.observationId() != null) throw new IllegalArgumentException();
            }
            if (r.observationId() != null) {
                Observation selected = observation(r.observationId());
                if (selected == null || selected.acceptedAt() == null || !serverWindow(r, selected.acceptedAt()) || !freshAtAdmission(selected)
                        || !targetAmountTime(r, selected.candidate()) || !r.id().equals(selected.requestId())
                        || actionable(r) && selected.disposition() != Disposition.MATCHED
                        || state.requests().stream().anyMatch(other -> !other.id().equals(r.id()) && referencesLifecycle(other, selected.candidate())))
                    throw new IllegalArgumentException();
                if (actionable(r) && state.observations().stream().anyMatch(o ->
                        sameLifecycle(o.candidate(), selected.candidate()) && !o.candidate().eventId().equals(selected.candidate().eventId())))
                    throw new IllegalArgumentException();
                if (grant != null && !grant.revoked() && actionable(r)
                        && state.observations().stream().anyMatch(o -> !o.candidate().eventId().equals(grant.eventId())
                            && o.candidate().amountMinor() == r.amountMinor()
                            && (o.acceptedAt() == null ? targetAmountTime(r, o.candidate())
                                : !o.acceptedAt().isBefore(r.createdAt()) && o.acceptedAt().isBefore(grant.reviewExpiresAt()))))
                    throw new IllegalArgumentException();
            } else if (r.status() == Status.PAYMENT_DETECTED || r.status() == Status.AWAITING_VERIFICATION
                    || r.status() == Status.MANUALLY_CONFIRMED) throw new IllegalArgumentException();
        }
    }

    synchronized Snapshot view() { expire(clock.instant()); return state; }
    synchronized Checkout checkout(UUID id) {
        Instant now = clock.instant(); expire(now);
        PaymentRequest r = state.requests().stream().filter(request -> request.id().equals(id)).findFirst()
                .orElseThrow(() -> new BridgeFailure(404, "CHECKOUT_NOT_FOUND"));
        return new Checkout(r.id(), r.amountMinor(), r.createdAt(), r.expiresAt(), r.status(), evidence(r),
                r.paymentClosed() || !now.isBefore(r.expiresAt()), r.reviewGrant() == null ? null : r.reviewGrant().reviewExpiresAt(),
                (r.status() == Status.PAYMENT_DETECTED || r.status() == Status.AWAITING_VERIFICATION)
                        && eligible(r, now) && hasEvidenceCapacity());
    }
    synchronized PaymentRequest create(CreateRequest input) {
        if (input == null) throw new BridgeFailure(422, "AMOUNT_INVALID");
        amount(input.amountMinor()); Instant now = clock.instant(); expire(now);
        return add(input.amountMinor(), null, now);
    }
    record IntegrationPut(IntegrationCheckout checkout, boolean created) {}
    /** Idempotent create-by-reference. The reference lookup precedes every hold and capacity check, so a replay is never blocked by its own hold. */
    synchronized IntegrationPut putIntegration(UUID reference, Integer amountMinor) {
        Objects.requireNonNull(reference);
        amount(amountMinor); Instant now = clock.instant(); expire(now);
        PaymentRequest bound = integration(reference);
        if (bound != null) {
            if (bound.amountMinor() != amountMinor) throw new BridgeFailure(409, "REFERENCE_CONFLICT");
            return new IntegrationPut(integrationView(bound), false);
        }
        // The reference binding and its new request persist in the same snapshot write.
        return new IntegrationPut(integrationView(add(amountMinor, reference, now)), true);
    }
    synchronized IntegrationCheckout integrationCheckout(UUID reference) {
        expire(clock.instant());
        PaymentRequest bound = integration(reference);
        if (bound == null) throw new BridgeFailure(404, "REFERENCE_NOT_FOUND");
        return integrationView(bound);
    }
    private PaymentRequest add(int amountMinor, UUID reference, Instant now) {
        if (state.requests().size() >= MAX_REQUESTS) throw new BridgeFailure(409, "REQUEST_CAPACITY");
        if (state.requests().stream().anyMatch(r -> r.amountMinor() == amountMinor && held(r, now)))
            throw new BridgeFailure(409, "SAME_AMOUNT_HELD");
        PaymentRequest r = new PaymentRequest(UUID.randomUUID(), amountMinor, now, now.plusSeconds(600),
                Status.AWAITING_PAYMENT, null, 2, false, null, 1, reference);
        List<PaymentRequest> requests = new ArrayList<>(state.requests()); requests.add(r);
        publish(new Snapshot(requests, state.observations())); return r;
    }
    private PaymentRequest integration(UUID reference) {
        return state.requests().stream().filter(r -> reference.equals(r.integrationReference())).findFirst().orElse(null);
    }
    private IntegrationCheckout integrationView(PaymentRequest r) {
        return new IntegrationCheckout(r.integrationReference(), r.id(), r.amountMinor(), r.status(), evidence(r), r.createdAt(),
                r.expiresAt(), r.reviewGrant() == null ? null : r.reviewGrant().reviewExpiresAt(), r.revision());
    }
    private PayerEvidence evidence(PaymentRequest r) {
        Observation selected = observation(r.observationId() != null ? r.observationId()
                : r.reviewGrant() != null ? r.reviewGrant().eventId() : null);
        return selected == null ? PayerEvidence.NONE
                : selected.candidate().source() == Source.TNG_NOTIFICATION_CANDIDATE ? PayerEvidence.NOTIFICATION_UNVERIFIED : PayerEvidence.SYNTHETIC;
    }
    synchronized Observation ingest(Candidate event) { return ingest(event, false); }
    synchronized Observation ingestNotification(Candidate event) {
        if (deviceId == null) throw new BridgeFailure(404, "LIVE_DELIVERY_DISABLED");
        validate(event, true);
        if (!deviceId.equals(event.deviceId())) throw new BridgeFailure(422, "DEVICE_SCOPE_INVALID");
        if (syntheticOnly && event.source() == Source.TNG_NOTIFICATION_CANDIDATE) {
            // Placed before expire(), disqualification or any other state change. Only an exact retry of an event
            // stored before integration mode is acknowledged, unchanged; no genuine candidate is stored in this mode.
            Observation existing = observation(event.eventId());
            if (existing != null && existing.candidate().equals(event)) return existing;
            throw new BridgeFailure(422, "GENUINE_DISABLED");
        }
        return ingest(event, true);
    }
    private Observation ingest(Candidate event, boolean live) {
        validate(event, live);
        Observation existing = observation(event.eventId());
        if (existing != null) {
            if (!existing.candidate().equals(event)) throw new BridgeFailure(409, "EVENT_ID_REUSED");
            return existing;
        }
        // Admission uses the serialized decision time, never device/HTTP arrival time.
        Instant now = clock.instant();
        if (event.observedAt().isBefore(now.minusSeconds(live ? 86400 : 120)) || event.observedAt().isAfter(now.plusSeconds(30)))
            throw new BridgeFailure(422, "EVENT_TIME_INVALID");
        expire(now);
        Observation last = state.observations().stream().filter(o -> sameLifecycle(o.candidate(), event)).reduce((first, next) -> next).orElse(null);
        if (last != null && (event.sequence() <= last.candidate().sequence() || event.observedAt().isBefore(last.candidate().observedAt())))
            throw new BridgeFailure(409, "NOTIFICATION_ORDER_INVALID");
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        List<Observation> observations = new ArrayList<>(state.observations());
        for (PaymentRequest r : List.copyOf(requests)) {
            if (!terminal(r) && (r.amountMinor() == event.amountMinor() && r.reviewGrant() != null
                    || last != null && referencesLifecycle(r, event))) disqualify(requests, observations, r);
        }
        if (!hasEvidenceCapacity()) {
            Snapshot revoked = new Snapshot(requests, observations);
            if (!revoked.equals(state) && store.serializedSize(revoked) <= SnapshotStore.MAX_BYTES) publish(revoked);
            throw new BridgeFailure(409, "OBSERVATION_CAPACITY");
        }
        Observation result;
        if (last != null) {
            for (int i = 0; i < observations.size(); i++) {
                Observation o = observations.get(i);
                if (sameLifecycle(o.candidate(), event) && o.disposition() == Disposition.MATCHED)
                    observations.set(i, o.withDisposition(Disposition.AMBIGUOUS));
            }
            result = new Observation(event, Disposition.UPDATE_IGNORED, last.requestId(), now);
        } else if (live && event.observedAt().isBefore(now.minusSeconds(120))) {
            result = new Observation(event, Disposition.DELAYED, null, now);
        } else {
            List<PaymentRequest> matches = requests.stream().filter(r -> !terminal(r) && paymentOpen(r, now) && targetAmountTime(r, event)).toList();
            boolean competing = observations.stream().anyMatch(o -> o.candidate().amountMinor().equals(event.amountMinor())
                    && matches.stream().anyMatch(r -> o.acceptedAt() == null ? targetAmountTime(r, o.candidate())
                        : !o.acceptedAt().isBefore(r.createdAt())));
            if (matches.size() == 1 && matches.getFirst().status() == Status.AWAITING_PAYMENT && !competing) {
                PaymentRequest match = matches.getFirst();
                ReviewGrant grant = v2(match) ? new ReviewGrant(event.eventId(), match.expiresAt().plusSeconds(reviewPeriodSeconds), false) : null;
                requests.set(requests.indexOf(match), copy(match, Status.PAYMENT_DETECTED, event.eventId(), match.paymentClosed(), grant));
                result = new Observation(event, Disposition.MATCHED, match.id(), now);
            } else if (!matches.isEmpty()) {
                for (PaymentRequest match : matches) disqualify(requests, observations, match);
                result = new Observation(event, Disposition.AMBIGUOUS, null, now);
            } else {
                boolean expired = requests.stream().anyMatch(r -> r.amountMinor() == event.amountMinor()
                        && (terminal(r) || !paymentOpen(r, now)) && !event.observedAt().isBefore(r.createdAt()));
                result = new Observation(event, expired ? Disposition.EXPIRED : Disposition.UNMATCHED, null, now);
            }
        }
        observations.add(result); publish(new Snapshot(requests, observations)); return result;
    }
    synchronized Observation resolve(UUID eventId, Resolution input) {
        if (input == null || !Boolean.TRUE.equals(input.historyChecked())) throw new BridgeFailure(422, "HISTORY_CHECK_REQUIRED");
        Instant now = clock.instant(); expire(now);
        Observation current = observation(eventId);
        if (current == null) throw new BridgeFailure(404, "OBSERVATION_NOT_FOUND");
        PaymentRequest target = request(input.requestId()); Candidate event = current.candidate();
        // Defence in depth: only labelled SYNTHETIC evidence may ever be resolved onto an integration checkout.
        if (event.source() == Source.SIMULATED || target.integrationReference() != null && event.source() != Source.SYNTHETIC || current.disposition() != Disposition.UNMATCHED && current.disposition() != Disposition.AMBIGUOUS
                || event.observedAt().isBefore(now.minusSeconds(120)) || !paymentOpen(target, now) || target.reviewGrant() != null
                || target.status() != Status.AWAITING_PAYMENT && target.status() != Status.AMBIGUOUS
                || !targetAmountTime(target, event) || v2(target) && (current.acceptedAt() == null || !serverWindow(target, current.acceptedAt()))
                || state.observations().stream().anyMatch(o -> sameLifecycle(o.candidate(), event)
                    && (o.candidate().sequence() > event.sequence() || o.disposition() == Disposition.MATCHED
                        || o.disposition() == Disposition.INVALIDATED || o.disposition() == Disposition.UPDATE_IGNORED))
                || state.requests().stream().anyMatch(r -> referencesLifecycle(r, event)))
            throw new BridgeFailure(409, "RESOLUTION_NOT_ELIGIBLE");
        Observation changed = new Observation(event, Disposition.MATCHED, target.id(), current.acceptedAt());
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        requests.set(requests.indexOf(target), target.withStatus(Status.PAYMENT_DETECTED, eventId));
        List<Observation> observations = new ArrayList<>(state.observations());
        observations.set(observations.indexOf(current), changed); publish(new Snapshot(requests, observations)); return changed;
    }
    synchronized Observation invalidate(UUID eventId, Invalidation input) {
        if (deviceId == null) throw new BridgeFailure(404, "LIVE_DELIVERY_DISABLED");
        if (input == null || !"NOTIFICATION_CHANGED".equals(input.reason())) throw new BridgeFailure(422, "INVALIDATION_INVALID");
        Instant now = clock.instant(); expire(now);
        Observation original = observation(eventId);
        if (original == null) throw new BridgeFailure(404, "OBSERVATION_NOT_FOUND");
        if (!deviceId.equals(original.candidate().deviceId())) throw new BridgeFailure(403, "DEVICE_SCOPE_INVALID");
        if (original.disposition() == Disposition.INVALIDATED) return original;
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        List<Observation> observations = new ArrayList<>(state.observations());
        for (PaymentRequest r : List.copyOf(requests)) if (!terminal(r) && referencesLifecycle(r, original.candidate()))
            disqualify(requests, observations, r);
        for (int i = 0; i < observations.size(); i++) if (sameLifecycle(observations.get(i).candidate(), original.candidate()))
            observations.set(i, observations.get(i).withDisposition(Disposition.INVALIDATED));
        publish(new Snapshot(requests, observations)); return observation(eventId);
    }
    synchronized PaymentRequest beginVerification(UUID id) { return beginVerification(id, new Confirmation(true)); }
    synchronized PaymentRequest beginVerification(UUID id, Confirmation input) {
        return transition(id, input, Status.PAYMENT_DETECTED, Status.AWAITING_VERIFICATION);
    }
    synchronized PaymentRequest confirm(UUID id, Confirmation input) { return transition(id, input, Status.AWAITING_VERIFICATION, Status.MANUALLY_CONFIRMED); }
    private PaymentRequest transition(UUID id, Confirmation input, Status from, Status to) {
        if (input == null || !Boolean.TRUE.equals(input.historyChecked())) throw new BridgeFailure(422, "HISTORY_CHECK_REQUIRED");
        Instant now = clock.instant(); expire(now); PaymentRequest current = request(id);
        if (v2(current) && (input.expectedEventId() == null || !input.expectedEventId().equals(current.observationId()))
                || input.expectedEventId() != null && !input.expectedEventId().equals(current.observationId()))
            throw new BridgeFailure(409, "VERIFICATION_NOT_ELIGIBLE");
        if (current.status() == Status.MANUALLY_CONFIRMED) return current;
        if (!hasEvidenceCapacity()) throw new BridgeFailure(503, "EVIDENCE_CAPACITY");
        if ((current.status() != from && current.status() != to) || !eligible(current, now))
            throw new BridgeFailure(409, "VERIFICATION_NOT_ELIGIBLE");
        if (current.status() == to) return current;
        PaymentRequest changed = current.withStatus(to, current.observationId());
        List<PaymentRequest> requests = new ArrayList<>(state.requests()); requests.set(requests.indexOf(current), changed);
        publish(new Snapshot(requests, state.observations())); return changed;
    }
    private boolean eligible(PaymentRequest r, Instant now) {
        Observation selected = observation(r.observationId());
        if (selected == null || selected.disposition() != Disposition.MATCHED || !r.id().equals(selected.requestId())
                || !targetAmountTime(r, selected.candidate()) || state.requests().stream().anyMatch(other ->
                    !other.id().equals(r.id()) && referencesLifecycle(other, selected.candidate()))
                || state.observations().stream().anyMatch(o -> sameLifecycle(o.candidate(), selected.candidate())
                    && !o.candidate().eventId().equals(selected.candidate().eventId()))) return false;
        if (v2(r) && (selected.acceptedAt() == null || !serverWindow(r, selected.acceptedAt()))) return false;
        if (paymentOpen(r, now)) return true;
        ReviewGrant grant = r.reviewGrant();
        return v2(r) && grant != null && !grant.revoked() && grant.eventId().equals(r.observationId())
                && now.isBefore(grant.reviewExpiresAt()) && !now.isBefore(r.createdAt());
    }
    private void expire(Instant now) {
        List<PaymentRequest> requests = state.requests().stream().map(r -> {
            boolean closed = r.paymentClosed() || !now.isBefore(r.expiresAt());
            if (terminal(r)) return v2(r) && closed != r.paymentClosed() ? copy(r, r.status(), r.observationId(), closed, r.reviewGrant()) : r;
            if (!closed) return r;
            if (v2(r) && r.reviewGrant() != null) {
                Status next = !now.isBefore(r.reviewGrant().reviewExpiresAt()) ? Status.REVIEW_EXPIRED : r.status();
                return copy(r, next, r.observationId(), true, r.reviewGrant());
            }
            return copy(r, Status.EXPIRED, null, v2(r) || r.paymentClosed(), null);
        }).toList();
        if (!requests.equals(state.requests())) publish(new Snapshot(requests, state.observations()));
    }
    private void disqualify(List<PaymentRequest> requests, List<Observation> observations, PaymentRequest r) {
        if (terminal(r)) return;
        ReviewGrant grant = r.reviewGrant();
        if (grant != null) grant = new ReviewGrant(grant.eventId(), grant.reviewExpiresAt(), true);
        requests.set(requests.indexOf(r), copy(r, Status.AMBIGUOUS, null, r.paymentClosed(), grant));
        for (int i = 0; i < observations.size(); i++) {
            Observation o = observations.get(i);
            if (r.id().equals(o.requestId()) && o.disposition() == Disposition.MATCHED)
                observations.set(i, o.withDisposition(Disposition.AMBIGUOUS));
        }
    }
    private boolean referencesLifecycle(PaymentRequest r, Candidate event) {
        Observation selected = observation(r.observationId());
        Observation granted = r.reviewGrant() == null ? null : observation(r.reviewGrant().eventId());
        return selected != null && sameLifecycle(selected.candidate(), event) || granted != null && sameLifecycle(granted.candidate(), event);
    }
    private boolean hasEvidenceCapacity() { return state.observations().size() < MAX_OBSERVATIONS && store.hasEvidenceHeadroom(state); }
    private static boolean held(PaymentRequest r, Instant now) {
        return paymentOpen(r, now) || r.status() != Status.MANUALLY_CONFIRMED && r.reviewGrant() != null && now.isBefore(r.reviewGrant().reviewExpiresAt());
    }
    private static boolean paymentOpen(PaymentRequest r, Instant now) { return !r.paymentClosed() && serverWindow(r, now); }
    private static boolean serverWindow(PaymentRequest r, Instant at) { return !at.isBefore(r.createdAt()) && at.isBefore(r.expiresAt()); }
    private static boolean targetAmountTime(PaymentRequest r, Candidate event) {
        return r.amountMinor() == event.amountMinor() && !event.observedAt().isBefore(r.createdAt()) && event.observedAt().isBefore(r.expiresAt());
    }
    private static boolean freshAtAdmission(Observation o) {
        return o.acceptedAt() != null && !o.candidate().observedAt().isBefore(o.acceptedAt().minusSeconds(120))
                && !o.candidate().observedAt().isAfter(o.acceptedAt().plusSeconds(30));
    }
    private static boolean sameLifecycle(Candidate first, Candidate second) {
        return first.notificationId().equals(second.notificationId()) && Objects.equals(first.deviceId(), second.deviceId());
    }
    private static boolean v2(PaymentRequest r) { return Integer.valueOf(2).equals(r.lifecycleVersion()); }
    private static boolean terminal(PaymentRequest r) { return r.status() == Status.EXPIRED || r.status() == Status.REVIEW_EXPIRED || r.status() == Status.MANUALLY_CONFIRMED; }
    private static boolean actionable(PaymentRequest r) { return r.status() == Status.PAYMENT_DETECTED || r.status() == Status.AWAITING_VERIFICATION; }
    private static PaymentRequest copy(PaymentRequest r, Status status, UUID event, boolean closed, ReviewGrant grant) {
        return r.changed(status, event, closed, grant);
    }
    private Observation observation(UUID id) { return id == null ? null : state.observations().stream().filter(o -> o.candidate().eventId().equals(id)).findFirst().orElse(null); }
    private PaymentRequest request(UUID id) { return state.requests().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new BridgeFailure(404, "REQUEST_NOT_FOUND")); }
    private void publish(Snapshot next) {
        Snapshot immutable = new Snapshot(List.copyOf(next.requests()), List.copyOf(next.observations())); store.save(immutable); state = immutable;
    }
    static void amount(Integer amount) {
        if (amount == null || amount <= 0 || amount > MAX_AMOUNT) throw new BridgeFailure(422, "AMOUNT_INVALID");
    }
    private static void validate(Candidate event, boolean live) {
        if (event == null || event.eventId() == null || event.notificationId() == null || event.sequence() == null
                || event.sequence() < 1 || event.sequence() > 1_000_000 || event.observedAt() == null || event.source() == null)
            throw new BridgeFailure(422, "EVENT_INVALID");
        if (!live && (event.source() != Source.SIMULATED || event.deviceId() != null || event.classification() != null
                || event.classifierProfile() != null || event.evidenceQuality() != null)) throw new BridgeFailure(422, "EVENT_INVALID");
        if (live && (event.deviceId() == null || !"INCOMING_CANDIDATE".equals(event.classification())
                || !((event.source() == Source.TNG_NOTIFICATION_CANDIDATE && "tng.receipt.en.observed.v1".equals(event.classifierProfile()) && "RULE_MATCHED".equals(event.evidenceQuality()))
                || (event.source() == Source.SYNTHETIC && "synthetic.debug.v1".equals(event.classifierProfile()) && "SYNTHETIC".equals(event.evidenceQuality())))))
            throw new BridgeFailure(422, "PROFILE_UNSUPPORTED");
        amount(event.amountMinor());
    }
}
