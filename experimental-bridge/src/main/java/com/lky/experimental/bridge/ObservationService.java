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
    private Snapshot state;

    ObservationService(SnapshotStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
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
                validate(observation.candidate());
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
        validate(event);
        // A lost acknowledgement may be retried after freshness expires: identical data returns existing result.
        for (Observation existing : state.observations()) {
            if (existing.candidate().eventId().equals(event.eventId())) {
                if (!existing.candidate().equals(event)) throw new BridgeFailure(409, "EVENT_ID_REUSED");
                return existing;
            }
        }
        Instant now = clock.instant();
        if (event.observedAt().isBefore(now.minusSeconds(120)) || event.observedAt().isAfter(now.plusSeconds(30)))
            throw new BridgeFailure(422, "EVENT_TIME_INVALID");
        expire();
        if (state.observations().size() >= MAX_OBSERVATIONS) throw new BridgeFailure(409, "OBSERVATION_CAPACITY");
        Observation last = state.observations().stream().filter(o -> o.candidate().notificationId().equals(event.notificationId()))
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
                if (previous.candidate().notificationId().equals(event.notificationId()) && previous.disposition() == Disposition.MATCHED)
                    observations.set(i, new Observation(previous.candidate(), Disposition.AMBIGUOUS, previous.requestId()));
            }
            observation = new Observation(event, Disposition.UPDATE_IGNORED, last.requestId());
        } else {
            List<PaymentRequest> matches = requests.stream().filter(r -> (r.status() == Status.AWAITING_PAYMENT || r.status() == Status.AMBIGUOUS)
                    && r.amountMinor() == event.amountMinor() && !event.observedAt().isBefore(r.createdAt())
                    && event.observedAt().isBefore(r.expiresAt())).toList();
            if (matches.size() == 1 && matches.getFirst().status() == Status.AWAITING_PAYMENT) {
                PaymentRequest match = matches.getFirst();
                requests.set(requests.indexOf(match), match.withStatus(Status.PAYMENT_DETECTED, event.eventId()));
                observation = new Observation(event, Disposition.MATCHED, match.id());
            } else if (!matches.isEmpty()) {
                for (PaymentRequest match : matches) requests.set(requests.indexOf(match), match.withStatus(Status.AMBIGUOUS, null));
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

    private static void validate(Candidate event) {
        if (event == null || event.eventId() == null || event.notificationId() == null || event.sequence() == null
                || event.sequence() < 1 || event.sequence() > 1_000_000 || event.observedAt() == null || event.source() != Source.SIMULATED)
            throw new BridgeFailure(422, "EVENT_INVALID");
        amount(event.amountMinor());
    }
}
