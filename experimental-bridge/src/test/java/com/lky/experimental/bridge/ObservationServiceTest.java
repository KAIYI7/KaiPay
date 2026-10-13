package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class ObservationServiceTest {
    @TempDir Path directory;
    private SnapshotStore store;
    private ObservationService service;
    private MutableClock clock;
    private ObjectMapper mapper;
    private Path file;
    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().build();
        file = directory.resolve("state.json");
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new SnapshotStore(file, mapper);
        service = new ObservationService(store, clock);
    }
    @AfterEach void close() throws Exception { store.close(); }
    private Candidate event(int amount) { return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.instant(), amount, Source.SIMULATED); }
    private void failure(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BridgeFailure.class, e -> {
            assertThat(e.status).isEqualTo(status); assertThat(e.code).isEqualTo(code);
        });
    }

    @Test void detectionNeedsTwoReceiverStepsAndNeverAutomaticallyConfirms() {
        PaymentRequest request = service.create(new CreateRequest(1234));
        Observation observation = service.ingest(event(1234));
        assertThat(observation.disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.PAYMENT_DETECTED);
        failure(() -> service.confirm(request.id(), new Confirmation(true)), 409, "VERIFICATION_NOT_ELIGIBLE");
        assertThat(service.beginVerification(request.id()).status()).isEqualTo(Status.AWAITING_VERIFICATION);
        failure(() -> service.confirm(request.id(), new Confirmation(false)), 422, "HISTORY_CHECK_REQUIRED");
        assertThat(service.confirm(request.id(), new Confirmation(true)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.confirm(request.id(), new Confirmation(true)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void amountsAreExactIntegerMinorUnitsAndBounded() {
        for (Integer amount : new Integer[]{null, -1, 0, 1_000_001}) failure(() -> service.create(new CreateRequest(amount)), 422, "AMOUNT_INVALID");
        assertThat(service.create(new CreateRequest(1)).amountMinor()).isEqualTo(1);
        assertThat(service.create(new CreateRequest(1_000_000)).amountMinor()).isEqualTo(1_000_000);
    }

    @Test void missingEventFieldsAndUnsupportedSourceAreRejectedWithoutPersistence() {
        Candidate good = event(1234);
        List<Candidate> invalid = List.of(new Candidate(null, good.notificationId(), 1, good.observedAt(), 1234, Source.SIMULATED),
                new Candidate(good.eventId(), null, 1, good.observedAt(), 1234, Source.SIMULATED),
                new Candidate(good.eventId(), good.notificationId(), 0, good.observedAt(), 1234, Source.SIMULATED),
                new Candidate(good.eventId(), good.notificationId(), null, good.observedAt(), 1234, Source.SIMULATED),
                new Candidate(good.eventId(), good.notificationId(), 1, null, 1234, Source.SIMULATED),
                new Candidate(good.eventId(), good.notificationId(), 1, good.observedAt(), 1234, null));
        for (Candidate input : invalid) failure(() -> service.ingest(input), 422, "EVENT_INVALID");
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void identicalDuplicateAndLateRetryRemainOneEvent() {
        Candidate input = event(1200);
        Observation first = service.ingest(input);
        clock.now = clock.now.plusSeconds(300);
        assertThat(service.ingest(input)).isEqualTo(first);
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void reusedEventIdWithAlteredPayloadIsAConflict() {
        Candidate input = event(1200); service.ingest(input);
        Candidate changed = new Candidate(input.eventId(), input.notificationId(), 1, input.observedAt(), 1300, Source.SIMULATED);
        failure(() -> service.ingest(changed), 409, "EVENT_ID_REUSED");
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void staleAndFutureEventsCannotCreateObservations() {
        Candidate input = event(1200);
        for (Instant timestamp : List.of(clock.now.minusSeconds(121), clock.now.plusSeconds(31))) {
            failure(() -> service.ingest(new Candidate(input.eventId(), input.notificationId(), 1, timestamp, 1200, Source.SIMULATED)), 422, "EVENT_TIME_INVALID");
        }
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void eventPredatingRequestRemainsUnmatched() {
        Candidate input = event(1200); clock.now = clock.now.plusSeconds(30);
        PaymentRequest request = service.create(new CreateRequest(1200));
        assertThat(service.ingest(input).disposition()).isEqualTo(Disposition.UNMATCHED);
        failure(() -> service.beginVerification(request.id()), 409, "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void multipleIdenticalPendingAmountsStayAmbiguous() {
        PaymentRequest one = service.create(new CreateRequest(1200));
        PaymentRequest two = service.create(new CreateRequest(1200));
        assertThat(service.ingest(event(1200)).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.view().requests()).allMatch(r -> r.status() == Status.AMBIGUOUS && r.observationId() == null);
        for (UUID id : List.of(one.id(), two.id())) failure(() -> service.beginVerification(id), 409, "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void ambiguityDoesNotBecomeCertaintyWhenOneRequestExpires() {
        service.create(new CreateRequest(1200)); clock.now = clock.now.plusSeconds(60);
        PaymentRequest second = service.create(new CreateRequest(1200));
        Candidate first = event(1200); service.ingest(first);
        clock.now = clock.now.plusSeconds(550);
        Candidate update = new Candidate(UUID.randomUUID(), first.notificationId(), 2, clock.now, 1200, Source.SIMULATED);
        assertThat(service.ingest(update).disposition()).isEqualTo(Disposition.UPDATE_IGNORED);
        assertThat(service.ingest(event(1200)).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        failure(() -> service.beginVerification(second.id()), 409, "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void updatedNotificationCannotMatchSecondRequestAndInvalidatesUnconfirmedMatch() {
        PaymentRequest one = service.create(new CreateRequest(1200));
        Candidate first = event(1200); service.ingest(first);
        service.beginVerification(one.id());
        PaymentRequest two = service.create(new CreateRequest(1300));
        Candidate update = new Candidate(UUID.randomUUID(), first.notificationId(), 2, clock.now, 1300, Source.SIMULATED);
        assertThat(service.ingest(update).disposition()).isEqualTo(Disposition.UPDATE_IGNORED);
        failure(() -> service.confirm(one.id(), new Confirmation(true)), 409, "VERIFICATION_NOT_ELIGIBLE");
        assertThat(service.view().requests()).anyMatch(r -> r.id().equals(two.id()) && r.status() == Status.AWAITING_PAYMENT);
    }

    @Test void outOfOrderNotificationsAreRejected() {
        Candidate first = event(1200); service.ingest(first);
        failure(() -> service.ingest(new Candidate(UUID.randomUUID(), first.notificationId(), 1, clock.now, 1200, Source.SIMULATED)), 409, "NOTIFICATION_ORDER_INVALID");
        failure(() -> service.ingest(new Candidate(UUID.randomUUID(), first.notificationId(), 2, clock.now.minusSeconds(1), 1200, Source.SIMULATED)), 409, "NOTIFICATION_ORDER_INVALID");
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void expiryAppliesBeforeMatchingAndBeforeManualActions() {
        PaymentRequest request = service.create(new CreateRequest(1200)); service.ingest(event(1200));
        service.beginVerification(request.id()); clock.now = clock.now.plusSeconds(600);
        failure(() -> service.confirm(request.id(), new Confirmation(true)), 409, "VERIFICATION_NOT_ELIGIBLE");
        assertThat(service.ingest(event(1200)).disposition()).isEqualTo(Disposition.EXPIRED);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.EXPIRED);
    }

    @Test void simultaneousIdenticalDuplicateDeliveriesHaveOneEffect() throws Exception {
        PaymentRequest request = service.create(new CreateRequest(1200));
        Candidate input = event(1200);
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return service.ingest(input); });
            var second = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return service.ingest(input); });
            barrier.await(5, TimeUnit.SECONDS);
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(service.view().observations()).hasSize(1);
        assertThat(service.view().requests().getFirst().id()).isEqualTo(request.id());
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.PAYMENT_DETECTED);
    }

    @Test void competingDistinctCandidatesForIdenticalAmountsCannotConfirmOrDoubleMatch() throws Exception {
        service.create(new CreateRequest(1200)); service.create(new CreateRequest(1200));
        Candidate one = event(1200), two = event(1200);
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return service.ingest(one); });
            var second = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return service.ingest(two); });
            barrier.await(5, TimeUnit.SECONDS);
            assertThat(first.get(5, TimeUnit.SECONDS).disposition()).isEqualTo(Disposition.AMBIGUOUS);
            assertThat(second.get(5, TimeUnit.SECONDS).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        }
        assertThat(service.view().requests()).allMatch(r -> r.status() == Status.AMBIGUOUS);
        assertThat(service.view().observations()).hasSize(2);
    }

    @Test void retainedReplayHistorySurvivesProcessRestart() throws Exception {
        Candidate input = event(1200); Observation first = service.ingest(input);
        store.close(); store = new SnapshotStore(file, mapper);
        service = new ObservationService(store, clock);
        assertThat(service.ingest(input)).isEqualTo(first);
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void diskFailureDoesNotPublishStateOrConsumeEventIdAndRetrySucceeds() throws Exception {
        service.create(new CreateRequest(1200));
        Files.delete(file); Files.createDirectory(file);
        Candidate input = event(1200);
        failure(() -> service.ingest(input), 503, "STORE_UNAVAILABLE");
        assertThat(service.view().observations()).isEmpty();
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.AWAITING_PAYMENT);
        Files.delete(file);
        assertThat(service.ingest(input).disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void secondStoreOwnerIsRefused() {
        assertThatThrownBy(() -> new SnapshotStore(file, mapper)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Experimental store unavailable");
    }

    @Test void fullReplayStoreRefusesGrowthRatherThanDeletingHistory() throws Exception {
        var observations = java.util.stream.IntStream.range(0, ObservationService.MAX_OBSERVATIONS)
                .mapToObj(i -> new Observation(event(1200), Disposition.UNMATCHED, null)).toList();
        store.save(new Snapshot(List.of(), observations));
        service = new ObservationService(store, clock);
        failure(() -> service.ingest(event(1200)), 409, "OBSERVATION_CAPACITY");
        assertThat(service.view().observations()).hasSize(1000);
        assertThat(service.ingest(observations.getFirst().candidate())).isEqualTo(observations.getFirst());
    }

    @Test void invalidStoredDataFailsClosedWithSanitizedError() {
        store.save(new Snapshot(List.of(new PaymentRequest(UUID.randomUUID(), -1, clock.now, clock.now.plusSeconds(600), Status.AWAITING_PAYMENT, null)), List.of()));
        assertThatThrownBy(() -> new ObservationService(store, clock)).hasMessage("Invalid experimental store");
    }

    @Test void fullRequestStoreRefusesNewRequestsWithoutRemovingReplayHistory() {
        var requests = java.util.stream.IntStream.range(0, ObservationService.MAX_REQUESTS).mapToObj(i ->
                new PaymentRequest(UUID.randomUUID(), 1200, clock.now, clock.now.plusSeconds(600), Status.AWAITING_PAYMENT, null)).toList();
        store.save(new Snapshot(requests, List.of()));
        service = new ObservationService(store, clock);
        failure(() -> service.create(new CreateRequest(1200)), 409, "REQUEST_CAPACITY");
        assertThat(service.view().requests()).hasSize(500);
    }

    @Test void confirmedManualStateIsNotReversedOrReplicatedByNotificationUpdate() {
        PaymentRequest request = service.create(new CreateRequest(1200));
        Candidate initial = event(1200); service.ingest(initial);
        service.beginVerification(request.id()); service.confirm(request.id(), new Confirmation(true));
        service.ingest(new Candidate(UUID.randomUUID(), initial.notificationId(), 2, clock.now, 1200, Source.SIMULATED));
        assertThat(service.view().requests()).hasSize(1);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void financeServicesAndInfrastructureCannotBeLoadedInIsolatedProcess() {
        for (String type : List.of("com.lky.kaipay.payment.service.PaymentService", "com.lky.kaipay.refund.service.RefundService",
                "com.lky.kaipay.ledger.service.LedgerService", "org.springframework.jdbc.core.JdbcTemplate", "org.apache.kafka.clients.producer.KafkaProducer")) {
            assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
        }
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
