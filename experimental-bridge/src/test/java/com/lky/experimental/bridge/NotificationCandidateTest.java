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

class NotificationCandidateTest {
    static final UUID DEVICE_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    MutableClock clock;
    ObjectMapper mapper;
    Path file;
    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().build();
        file = directory.resolve("state.json");
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new SnapshotStore(file, mapper);
        service = new ObservationService(store, clock, DEVICE_ID);
    }
    @AfterEach void close() throws Exception { store.close(); }
    Candidate candidate() { return candidate(Source.SYNTHETIC, clock.now, UUID.randomUUID(), 1); }
    Candidate candidate(Source source, Instant observedAt, UUID lifecycle, int sequence) {
        return new Candidate(UUID.randomUUID(), lifecycle, sequence, observedAt, 1234, source, DEVICE_ID, "INCOMING_CANDIDATE",
                source == Source.SYNTHETIC ? "synthetic.debug.v1" : "tng.receipt.en.observed.v1",
                source == Source.SYNTHETIC ? "SYNTHETIC" : "RULE_MATCHED");
    }
    void rejected(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOfSatisfying(BridgeFailure.class,
            failure -> assertThat(failure.code).isEqualTo(code)); }

    @Test void realAndSyntheticSourcesStayDistinctAndNeverAutomaticallyConfirm() {
        PaymentRequest request = service.create(new CreateRequest(1234));
        Candidate real = candidate(Source.TNG_NOTIFICATION_CANDIDATE, clock.now, UUID.randomUUID(), 1);
        assertThat(service.ingestNotification(real).disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(service.view().observations().getFirst().candidate().source()).isEqualTo(Source.TNG_NOTIFICATION_CANDIDATE);
        rejected(() -> service.confirm(request.id(), new Confirmation(true)), "VERIFICATION_NOT_ELIGIBLE");
        service.beginVerification(request.id());
        assertThat(service.confirm(request.id(), new Confirmation(true)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.ingestNotification(candidate()).candidate().source()).isEqualTo(Source.SYNTHETIC);
    }

    @Test void profileClassificationEvidenceAndDeviceScopeAreStrict() {
        Candidate good = candidate();
        for (Candidate bad : List.of(
                new Candidate(good.eventId(), good.notificationId(), 1, clock.now, 1234, Source.SYNTHETIC, DEVICE_ID, "UNKNOWN", "synthetic.debug.v1", "SYNTHETIC"),
                new Candidate(good.eventId(), good.notificationId(), 1, clock.now, 1234, Source.SYNTHETIC, DEVICE_ID, "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED"),
                new Candidate(good.eventId(), good.notificationId(), 1, clock.now, 1234, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE_ID, "INCOMING_CANDIDATE", "unreviewed", "RULE_MATCHED")))
            rejected(() -> service.ingestNotification(bad), "PROFILE_UNSUPPORTED");
        Candidate wrongDevice = new Candidate(good.eventId(), good.notificationId(), 1, clock.now, 1234, good.source(), UUID.randomUUID(), good.classification(), good.classifierProfile(), good.evidenceQuality());
        rejected(() -> service.ingestNotification(wrongDevice), "DEVICE_SCOPE_INVALID");
        rejected(() -> service.ingest(good), "EVENT_INVALID");
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void delayedIsReviewOnlyObsoleteAndFutureRejectedAndLegacyFreshnessPreserved() {
        PaymentRequest request = service.create(new CreateRequest(1234));
        clock.now = clock.now.plusSeconds(121);
        Candidate delayed = candidate(Source.SYNTHETIC, clock.now.minusSeconds(121), UUID.randomUUID(), 1);
        assertThat(service.ingestNotification(delayed).disposition()).isEqualTo(Disposition.DELAYED);
        rejected(() -> service.resolve(delayed.eventId(), new Resolution(request.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        rejected(() -> service.ingestNotification(candidate(Source.SYNTHETIC, clock.now.minusSeconds(86401), UUID.randomUUID(), 1)), "EVENT_TIME_INVALID");
        rejected(() -> service.ingestNotification(candidate(Source.SYNTHETIC, clock.now.plusSeconds(31), UUID.randomUUID(), 1)), "EVENT_TIME_INVALID");
        rejected(() -> service.ingest(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now.minusSeconds(121), 1234, Source.SIMULATED)), "EVENT_TIME_INVALID");
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.AWAITING_PAYMENT);
    }

    @Test void lostAcknowledgementRetrySurvivesRestartAndAgeAndRejectsChangedPayload() throws Exception {
        Candidate event = candidate();
        Observation accepted = service.ingestNotification(event);
        store.close(); store = new SnapshotStore(file, mapper);
        clock.now = clock.now.plusSeconds(90000);
        service = new ObservationService(store, clock, DEVICE_ID);
        assertThat(service.ingestNotification(event)).isEqualTo(accepted);
        Candidate changed = new Candidate(event.eventId(), event.notificationId(), 1, event.observedAt(), 1235, event.source(), DEVICE_ID, event.classification(), event.classifierProfile(), event.evidenceQuality());
        rejected(() -> service.ingestNotification(changed), "EVENT_ID_REUSED");
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void distinctCandidatesInvalidateExistingUnconfirmedAssociation() {
        PaymentRequest request = service.create(new CreateRequest(1234));
        service.ingestNotification(candidate());
        service.beginVerification(request.id());
        assertThat(service.ingestNotification(candidate()).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(service.view().observations()).allMatch(o -> o.disposition() == Disposition.AMBIGUOUS);
        rejected(() -> service.confirm(request.id(), new Confirmation(true)), "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void identicalAmountAmbiguityCanOnlyBeExplicitlyResolvedOnceWithHistoryAttestation() {
        PaymentRequest one = service.create(new CreateRequest(1234));
        PaymentRequest two = service.create(new CreateRequest(1234));
        Candidate event = candidate(); service.ingestNotification(event);
        rejected(() -> service.resolve(event.eventId(), new Resolution(one.id(), false)), "HISTORY_CHECK_REQUIRED");
        assertThat(service.resolve(event.eventId(), new Resolution(two.id(), true)).requestId()).isEqualTo(two.id());
        rejected(() -> service.resolve(event.eventId(), new Resolution(one.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        rejected(() -> service.confirm(two.id(), new Confirmation(true)), "VERIFICATION_NOT_ELIGIBLE");
        service.beginVerification(two.id()); service.confirm(two.id(), new Confirmation(true));
        assertThat(service.view().requests().stream().filter(r -> r.status() == Status.MANUALLY_CONFIRMED)).hasSize(1);
        rejected(() -> service.resolve(event.eventId(), new Resolution(one.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
    }

    @Test void unmatchedCannotResolveToLaterRequestWrongAmountOrExpiredRequest() {
        Candidate earlier = candidate(); service.ingestNotification(earlier);
        clock.now = clock.now.plusSeconds(1);
        PaymentRequest later = service.create(new CreateRequest(1234));
        rejected(() -> service.resolve(earlier.eventId(), new Resolution(later.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        PaymentRequest wrong = service.create(new CreateRequest(1235));
        rejected(() -> service.resolve(earlier.eventId(), new Resolution(wrong.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        clock.now = clock.now.plusSeconds(600);
        rejected(() -> service.resolve(earlier.eventId(), new Resolution(later.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        assertThat(service.ingestNotification(candidate()).disposition()).isEqualTo(Disposition.EXPIRED);
    }

    @Test void supportedUpdatesCannotMatchAnotherRequestOrResolveOldLifecycle() {
        PaymentRequest one = service.create(new CreateRequest(1234));
        Candidate event = candidate(); service.ingestNotification(event);
        PaymentRequest two = service.create(new CreateRequest(1234));
        Candidate update = candidate(Source.SYNTHETIC, clock.now, event.notificationId(), 2);
        assertThat(service.ingestNotification(update).disposition()).isEqualTo(Disposition.UPDATE_IGNORED);
        rejected(() -> service.resolve(event.eventId(), new Resolution(one.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        rejected(() -> service.resolve(update.eventId(), new Resolution(two.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        rejected(() -> service.ingestNotification(candidate(Source.SYNTHETIC, clock.now, event.notificationId(), 1)), "NOTIFICATION_ORDER_INVALID");
    }

    @Test void unsupportedChangeInvalidationPersistsAndPreventsVerificationButKeepsManualConfirmation() throws Exception {
        PaymentRequest request = service.create(new CreateRequest(1234));
        Candidate event = candidate(); service.ingestNotification(event); service.beginVerification(request.id());
        Observation invalidated = service.invalidate(event.eventId(), new Invalidation("NOTIFICATION_CHANGED"));
        assertThat(invalidated.disposition()).isEqualTo(Disposition.INVALIDATED);
        assertThat(service.invalidate(event.eventId(), new Invalidation("NOTIFICATION_CHANGED"))).isEqualTo(invalidated);
        rejected(() -> service.confirm(request.id(), new Confirmation(true)), "VERIFICATION_NOT_ELIGIBLE");
        rejected(() -> service.resolve(event.eventId(), new Resolution(request.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        store.close(); store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE_ID);
        assertThat(service.ingestNotification(event).disposition()).isEqualTo(Disposition.INVALIDATED);
        rejected(() -> service.invalidate(UUID.randomUUID(), new Invalidation("NOTIFICATION_CHANGED")), "OBSERVATION_NOT_FOUND");
        rejected(() -> service.invalidate(event.eventId(), new Invalidation("raw text")), "INVALIDATION_INVALID");
        PaymentRequest another = service.create(new CreateRequest(1235));
        Candidate different = candidate();
        different = new Candidate(different.eventId(), different.notificationId(), 1, clock.now, 1235, different.source(), DEVICE_ID, different.classification(), different.classifierProfile(), different.evidenceQuality());
        service.ingestNotification(different); service.beginVerification(another.id()); service.confirm(another.id(), new Confirmation(true));
        service.invalidate(different.eventId(), new Invalidation("NOTIFICATION_CHANGED"));
        assertThat(service.view().requests().stream().filter(r -> r.id().equals(another.id())).findFirst().orElseThrow().status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void persistenceFailureDoesNotConsumeCandidateOrExposeTentativeMatch() throws Exception {
        service.create(new CreateRequest(1234));
        Files.delete(file); Files.createDirectory(file);
        Candidate event = candidate();
        rejected(() -> service.ingestNotification(event), "STORE_UNAVAILABLE");
        assertThat(service.view().observations()).isEmpty();
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.AWAITING_PAYMENT);
        Files.delete(file);
        assertThat(service.ingestNotification(event).disposition()).isEqualTo(Disposition.MATCHED);
    }

    @Test void simultaneousRetryHasOneEffectAndDistinctCandidateConflictsAreSerialized() throws Exception {
        service.create(new CreateRequest(1234)); Candidate event = candidate();
        CyclicBarrier barrier = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return service.ingestNotification(event); });
            var second = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return service.ingestNotification(event); });
            barrier.await(5, TimeUnit.SECONDS);
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(service.view().observations()).hasSize(1);
        CyclicBarrier competing = new CyclicBarrier(3);
        Candidate one = candidate(), two = candidate();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { competing.await(5, TimeUnit.SECONDS); return service.ingestNotification(one); });
            var second = pool.submit(() -> { competing.await(5, TimeUnit.SECONDS); return service.ingestNotification(two); });
            competing.await(5, TimeUnit.SECONDS);
            assertThat(first.get(5, TimeUnit.SECONDS).disposition()).isEqualTo(Disposition.AMBIGUOUS);
            assertThat(second.get(5, TimeUnit.SECONDS).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        }
        assertThat(service.view().observations()).hasSize(3).allMatch(o -> o.disposition() == Disposition.AMBIGUOUS);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.AMBIGUOUS);
        rejected(() -> service.beginVerification(service.view().requests().getFirst().id()), "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void historicalSimulationSnapshotWithoutNewMetadataStillRestarts() throws Exception {
        Candidate simulated = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 1234, Source.SIMULATED);
        service.ingest(simulated);
        var tree = mapper.readTree(file.toFile());
        var fields = (com.fasterxml.jackson.databind.node.ObjectNode) tree.path("observations").get(0).path("candidate");
        fields.remove(List.of("deviceId", "classification", "classifierProfile", "evidenceQuality"));
        Files.writeString(file, mapper.writeValueAsString(tree));
        store.close(); store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE_ID);
        assertThat(service.ingest(simulated).candidate()).isEqualTo(simulated);
        assertThat(service.view().observations()).hasSize(1);
    }

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
