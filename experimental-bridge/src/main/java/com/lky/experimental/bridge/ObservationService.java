package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All mutations are serialized and persisted before becoming visible to callers. */
final class ObservationService {
    static final int MAX_REQUESTS = 500, MAX_OBSERVATIONS = 1000, MAX_AMOUNT = 1_000_000;
    private final SnapshotStore store;
    private final Clock clock;
    private final UUID deviceId;
    private Snapshot state;

    ObservationService(SnapshotStore store, Clock clock) {
        this(store, clock, null);
    }

    ObservationService(SnapshotStore store, Clock clock, UUID deviceId) {
        this.store = store;
        this.clock = clock;
        this.deviceId = deviceId;
        this.state = store.load();
        if (state.requests() == null || state.observations() == null || state.requests().size() > MAX_REQUESTS
                || state.observations().size() > MAX_OBSERVATIONS) throw new IllegalStateException("Invalid experimental store");
        try {
            for (PaymentRequest request : state.requests()) {
                amount(request.amountMinor());
                if (request.id() == null || request.createdAt() == null || request.expiresAt() == null || request.status() == null
                        || !request.expiresAt().equals(request.createdAt().plusSeconds(600))) throw new IllegalArgumentException();
            }
            for (Observation observation : state.observations()) {
                validate(observation.candidate(), observation.candidate().source() != Source.SIMULATED);
                if (observation.disposition() == null) throw new IllegalArgumentException();
            }
            if (state.requests().stream().map(PaymentRequest::id).distinct().count() != state.requests().size()
                    || state.observations().stream().map(o -> o.candidate().eventId()).distinct().count() != state.observations().size())
                throw new IllegalArgumentException();
        } catch (Exception ignored) { throw new IllegalStateException("Invalid experimental store"); }
        state = new Snapshot(List.copyOf(state.requests()), List.copyOf(state.observations()));
    }

    synchronized Snapshot view() { expire(); return state; }

    synchronized PaymentRequest create(CreateRequest input) {
        amount(input.amountMinor());
        expire();
        if (state.requests().size() >= MAX_REQUESTS) throw new BridgeFailure(409, "REQUEST_CAPACITY");
        Instant now = clock.instant();
        PaymentRequest request = new PaymentRequest(UUID.randomUUID(), input.amountMinor(), now, now.plusSeconds(600), Status.AWAITING_PAYMENT, null);
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        requests.add(request);
        publish(new Snapshot(requests, state.observations()));
        return request;
    }

    synchronized Observation ingest(Candidate event) {
        return ingest(event, false);
    }

    synchronized Observation ingestNotification(Candidate event) {
        if (deviceId == null) throw new BridgeFailure(404, "LIVE_DELIVERY_DISABLED");
        validate(event, true);
        if (!deviceId.equals(event.deviceId())) throw new BridgeFailure(422, "DEVICE_SCOPE_INVALID");
        return ingest(event, true);
    }

    private Observation ingest(Candidate event, boolean live) {
        validate(event, live);
        // A lost acknowledgement may be retried after freshness expires: identical data returns existing result.
        for (Observation existing : state.observations()) {
            if (existing.candidate().eventId().equals(event.eventId())) {
                if (!existing.candidate().equals(event)) throw new BridgeFailure(409, "EVENT_ID_REUSED");
                return existing;
            }
        }
        Instant now = clock.instant();
        if (event.observedAt().isBefore(now.minusSeconds(live ? 86400 : 120)) || event.observedAt().isAfter(now.plusSeconds(30)))
            throw new BridgeFailure(422, "EVENT_TIME_INVALID");
        expire();
        if (state.observations().size() >= MAX_OBSERVATIONS) throw new BridgeFailure(409, "OBSERVATION_CAPACITY");
        Observation last = state.observations().stream().filter(o -> sameLifecycle(o.candidate(), event))
                .reduce((first, next) -> next).orElse(null);
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        List<Observation> observations = new ArrayList<>(state.observations());
        Observation observation;
        if (last != null) {
            if (event.sequence() <= last.candidate().sequence() || event.observedAt().isBefore(last.candidate().observedAt()))
                throw new BridgeFailure(409, "NOTIFICATION_ORDER_INVALID");
            // An update cannot be a second payment; it also removes unverified match eligibility.
            for (int i = 0; i < requests.size(); i++) {
                PaymentRequest request = requests.get(i);
                if (request.id().equals(last.requestId()) && request.status() != Status.MANUALLY_CONFIRMED) {
                    requests.set(i, request.withStatus(request.status() == Status.EXPIRED ? Status.EXPIRED : Status.AMBIGUOUS, null));
                }
            }
            for (int i = 0; i < observations.size(); i++) {
                Observation previous = observations.get(i);
                if (sameLifecycle(previous.candidate(), event) && previous.disposition() == Disposition.MATCHED)
                    observations.set(i, new Observation(previous.candidate(), Disposition.AMBIGUOUS, previous.requestId()));
            }
            observation = new Observation(event, Disposition.UPDATE_IGNORED, last.requestId());
        } else if (live && event.observedAt().isBefore(now.minusSeconds(120))) {
            observation = new Observation(event, Disposition.DELAYED, null);
        } else {
            List<PaymentRequest> matches = requests.stream().filter(r -> (r.status() == Status.AWAITING_PAYMENT || r.status() == Status.AMBIGUOUS
                    || live && (r.status() == Status.PAYMENT_DETECTED || r.status() == Status.AWAITING_VERIFICATION))
                    && r.amountMinor() == event.amountMinor() && !event.observedAt().isBefore(r.createdAt())
                    && event.observedAt().isBefore(r.expiresAt())).toList();
            boolean competing = live && observations.stream().anyMatch(o -> o.candidate().source() != Source.SIMULATED
                    && (o.disposition() == Disposition.MATCHED || o.disposition() == Disposition.AMBIGUOUS || o.disposition() == Disposition.UNMATCHED)
                    && o.candidate().amountMinor().equals(event.amountMinor())
                    && matches.stream().anyMatch(r -> !o.candidate().observedAt().isBefore(r.createdAt())
                    && o.candidate().observedAt().isBefore(r.expiresAt())));
            if (matches.size() == 1 && matches.getFirst().status() == Status.AWAITING_PAYMENT && !competing) {
                PaymentRequest match = matches.getFirst();
                requests.set(requests.indexOf(match), match.withStatus(Status.PAYMENT_DETECTED, event.eventId()));
                observation = new Observation(event, Disposition.MATCHED, match.id());
            } else if (!matches.isEmpty()) {
                for (PaymentRequest match : matches) requests.set(requests.indexOf(match), match.withStatus(Status.AMBIGUOUS, null));
                if (live) {
                    for (int i = 0; i < observations.size(); i++) {
                        Observation previous = observations.get(i);
                        if (previous.disposition() == Disposition.MATCHED && matches.stream().anyMatch(r -> r.id().equals(previous.requestId())))
                            observations.set(i, new Observation(previous.candidate(), Disposition.AMBIGUOUS, previous.requestId()));
                    }
                }
                observation = new Observation(event, Disposition.AMBIGUOUS, null);
            } else {
                boolean expired = requests.stream().anyMatch(r -> r.status() == Status.EXPIRED && r.amountMinor() == event.amountMinor()
                        && !event.observedAt().isBefore(r.createdAt()));
                observation = new Observation(event, expired ? Disposition.EXPIRED : Disposition.UNMATCHED, null);
            }
        }
        observations.add(observation);
        publish(new Snapshot(requests, observations));
        return observation;
    }

    synchronized Observation resolve(UUID eventId, Resolution input) {
        if (input == null || !Boolean.TRUE.equals(input.historyChecked())) throw new BridgeFailure(422, "HISTORY_CHECK_REQUIRED");
        expire();
        Observation current = state.observations().stream().filter(o -> o.candidate().eventId().equals(eventId))
                .findFirst().orElseThrow(() -> new BridgeFailure(404, "OBSERVATION_NOT_FOUND"));
        PaymentRequest target = state.requests().stream().filter(r -> r.id().equals(input.requestId()))
                .findFirst().orElseThrow(() -> new BridgeFailure(404, "REQUEST_NOT_FOUND"));
        Candidate event = current.candidate();
        if (event.source() == Source.SIMULATED || (current.disposition() != Disposition.UNMATCHED && current.disposition() != Disposition.AMBIGUOUS)
                || event.observedAt().isBefore(clock.instant().minusSeconds(120))
                || (target.status() != Status.AWAITING_PAYMENT && target.status() != Status.AMBIGUOUS)
                || !targetAmountTime(target, event)
                || state.observations().stream().anyMatch(o -> sameLifecycle(o.candidate(), event)
                    && (o.candidate().sequence() > event.sequence() || o.disposition() == Disposition.MATCHED))
                || state.requests().stream().anyMatch(r -> r.observationId() != null && state.observations().stream().anyMatch(o ->
                    o.candidate().eventId().equals(r.observationId()) && sameLifecycle(o.candidate(), event))))
            throw new BridgeFailure(409, "RESOLUTION_NOT_ELIGIBLE");
        Observation changed = new Observation(event, Disposition.MATCHED, target.id());
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        requests.set(requests.indexOf(target), target.withStatus(Status.PAYMENT_DETECTED, eventId));
        List<Observation> observations = new ArrayList<>(state.observations());
        observations.set(observations.indexOf(current), changed);
        publish(new Snapshot(requests, observations));
        return changed;
    }

    synchronized Observation invalidate(UUID eventId, Invalidation input) {
        if (deviceId == null) throw new BridgeFailure(404, "LIVE_DELIVERY_DISABLED");
        if (input == null || !"NOTIFICATION_CHANGED".equals(input.reason())) throw new BridgeFailure(422, "INVALIDATION_INVALID");
        Observation original = state.observations().stream().filter(o -> o.candidate().eventId().equals(eventId))
                .findFirst().orElseThrow(() -> new BridgeFailure(404, "OBSERVATION_NOT_FOUND"));
        if (!deviceId.equals(original.candidate().deviceId())) throw new BridgeFailure(403, "DEVICE_SCOPE_INVALID");
        if (original.disposition() == Disposition.INVALIDATED) return original;
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        List<Observation> observations = new ArrayList<>(state.observations());
        for (int i = 0; i < observations.size(); i++) {
            Observation previous = observations.get(i);
            if (sameLifecycle(previous.candidate(), original.candidate())) {
                observations.set(i, new Observation(previous.candidate(), Disposition.INVALIDATED, previous.requestId()));
                for (int j = 0; j < requests.size(); j++) {
                    PaymentRequest request = requests.get(j);
                    if (previous.candidate().eventId().equals(request.observationId()) && request.status() != Status.MANUALLY_CONFIRMED)
                        requests.set(j, request.withStatus(request.status() == Status.EXPIRED ? Status.EXPIRED : Status.AMBIGUOUS, null));
                }
            }
        }
        publish(new Snapshot(requests, observations));
        return state.observations().stream().filter(o -> o.candidate().eventId().equals(eventId)).findFirst().orElseThrow();
    }

    private static boolean targetAmountTime(PaymentRequest request, Candidate event) {
        return request.amountMinor() == event.amountMinor() && !event.observedAt().isBefore(request.createdAt())
                && event.observedAt().isBefore(request.expiresAt());
    }

    private static boolean sameLifecycle(Candidate first, Candidate second) {
        return first.notificationId().equals(second.notificationId()) && java.util.Objects.equals(first.deviceId(), second.deviceId());
    }

    synchronized PaymentRequest beginVerification(UUID id) { return transition(id, Status.PAYMENT_DETECTED, Status.AWAITING_VERIFICATION); }

    synchronized PaymentRequest confirm(UUID id, Confirmation input) {
        if (!Boolean.TRUE.equals(input.historyChecked())) throw new BridgeFailure(422, "HISTORY_CHECK_REQUIRED");
        return transition(id, Status.AWAITING_VERIFICATION, Status.MANUALLY_CONFIRMED);
    }

    private PaymentRequest transition(UUID id, Status from, Status to) {
        expire();
        PaymentRequest current = state.requests().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> new BridgeFailure(404, "REQUEST_NOT_FOUND"));
        if (current.status() == to) return current;
        if (current.status() != from || current.observationId() == null || state.observations().stream().noneMatch(o ->
                o.candidate().eventId().equals(current.observationId()) && o.disposition() == Disposition.MATCHED))
            throw new BridgeFailure(409, "VERIFICATION_NOT_ELIGIBLE");
        PaymentRequest changed = current.withStatus(to, current.observationId());
        List<PaymentRequest> requests = new ArrayList<>(state.requests());
        requests.set(requests.indexOf(current), changed);
        publish(new Snapshot(requests, state.observations()));
        return changed;
    }

    private void expire() {
        Instant now = clock.instant();
        List<PaymentRequest> requests = state.requests().stream().map(r -> r.status() != Status.MANUALLY_CONFIRMED && r.status() != Status.EXPIRED
                && !now.isBefore(r.expiresAt()) ? r.withStatus(Status.EXPIRED, null) : r).toList();
        if (!requests.equals(state.requests())) publish(new Snapshot(requests, state.observations()));
    }

    private void publish(Snapshot next) {
        Snapshot immutable = new Snapshot(List.copyOf(next.requests()), List.copyOf(next.observations()));
        store.save(immutable);
        state = immutable;
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
