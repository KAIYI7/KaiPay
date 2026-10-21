package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class LifecycleTest {
    static final UUID DEVICE = NotificationCandidateTest.DEVICE_ID;
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    TestClock clock;
    Path file;

    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        clock = new TestClock(); file = directory.resolve("state.json");
        store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE, 1800);
    }
    @AfterEach void close() throws Exception { store.close(); }
    PaymentRequest create(int amount) { return service.create(new CreateRequest(amount)); }
    PaymentRequest current(UUID id) { return service.view().requests().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); }
    Candidate candidate(int amount, Source source) {
        return source == Source.SIMULATED
                ? new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.instant(), amount, source)
                : new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.instant(), amount, source, DEVICE,
                    "INCOMING_CANDIDATE", source == Source.SYNTHETIC ? "synthetic.debug.v1" : "tng.receipt.en.observed.v1",
                    source == Source.SYNTHETIC ? "SYNTHETIC" : "RULE_MATCHED");
    }
    Observation ingest(Candidate event) { return event.source() == Source.SIMULATED ? service.ingest(event) : service.ingestNotification(event); }
    Confirmation yes(Candidate event) { return new Confirmation(true, event.eventId()); }
    void fails(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOfSatisfying(BridgeFailure.class, failure -> assertThat(failure.code).isEqualTo(code)); }
    void restart(int seconds) throws Exception {
        store.close(); store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE, seconds);
    }

    @Test void newRequestsHaveVersionTwoAndNoGrantUntilUniqueAdmission() {
        PaymentRequest request = create(1234);
        assertThat(request.lifecycleVersion()).isEqualTo(2);
        assertThat(request.expiresAt()).isEqualTo(START.plusSeconds(600));
        assertThat(request.reviewGrant()).isNull();
        assertThat(request.paymentClosed()).isFalse();
        assertThat(service.checkout(request.id()).reviewAvailable()).isFalse();
    }

    @Test void noCandidateExpiresAtExactPaymentDeadlineAndCannotBeRevived() throws Exception {
        PaymentRequest request = create(1234);
        clock.now = request.expiresAt().minusNanos(1);
        assertThat(current(request.id()).status()).isEqualTo(Status.AWAITING_PAYMENT);
        clock.now = request.expiresAt();
        assertThat(current(request.id()).status()).isEqualTo(Status.EXPIRED);
        Candidate late = candidate(1234, Source.SYNTHETIC);
        assertThat(ingest(late).requestId()).isNull();
        fails(() -> service.beginVerification(request.id(), yes(late)), "VERIFICATION_NOT_ELIGIBLE");
        restart(1800); clock.now = START;
        assertThat(current(request.id()).status()).isEqualTo(Status.EXPIRED);
    }

    @Test void everySourceCanReviewUniquePreDeadlineMatchButNeverAutomaticallyConfirms() {
        int amount = 1200;
        for (Source source : Source.values()) {
            PaymentRequest request = create(++amount);
            clock.now = request.expiresAt().minusNanos(1);
            Candidate event = candidate(amount, source); Observation accepted = ingest(event);
            assertThat(accepted.acceptedAt()).isEqualTo(clock.now);
            PaymentRequest detected = current(request.id());
            assertThat(detected.status()).isEqualTo(Status.PAYMENT_DETECTED);
            assertThat(detected.reviewGrant().eventId()).isEqualTo(event.eventId());
            assertThat(detected.reviewGrant().reviewExpiresAt()).isEqualTo(request.expiresAt().plusSeconds(1800));
            clock.now = request.expiresAt();
            assertThat(service.checkout(request.id()).paymentWindowClosed()).isTrue();
            assertThat(service.checkout(request.id()).reviewAvailable()).isTrue();
            assertThat(current(request.id()).status()).isEqualTo(Status.PAYMENT_DETECTED);
            fails(() -> service.confirm(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
            service.beginVerification(request.id(), yes(event));
            assertThat(service.checkout(request.id()).status()).isEqualTo(Status.AWAITING_VERIFICATION);
            assertThat(service.confirm(request.id(), yes(event)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        }
    }

    @Test void deviceTimeCannotBackdateServerAdmissionAtPaymentDeadline() {
        PaymentRequest request = create(1234); clock.now = request.expiresAt().minusSeconds(1);
        Candidate event = candidate(1234, Source.SYNTHETIC);
        clock.now = request.expiresAt();
        Observation accepted = ingest(event);
        assertThat(accepted.acceptedAt()).isEqualTo(request.expiresAt());
        assertThat(accepted.requestId()).isNull();
        assertThat(current(request.id()).reviewGrant()).isNull();
    }

    @Test void lockAcquisitionAfterDeadlineCannotUseEarlierHttpArrival() throws Exception {
        PaymentRequest request = create(1234); clock.now = request.expiresAt().minusNanos(1);
        Candidate event = candidate(1234, Source.SYNTHETIC); CountDownLatch started = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Observation> result;
            synchronized (service) {
                result = pool.submit(() -> { started.countDown(); return ingest(event); });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = request.expiresAt();
            }
            assertThat(result.get(5, TimeUnit.SECONDS).requestId()).isNull();
        }
        assertThat(current(request.id()).status()).isEqualTo(Status.EXPIRED);
    }

    @Test void reviewEndsAtExactBoundaryForDetectedAndVerifiedStates() {
        for (boolean verify : new boolean[]{false, true}) {
            PaymentRequest request = create(verify ? 1235 : 1234); Candidate event = candidate(request.amountMinor(), Source.SYNTHETIC);
            ingest(event); if (verify) service.beginVerification(request.id(), yes(event));
            Instant end = current(request.id()).reviewGrant().reviewExpiresAt();
            clock.now = end.minusNanos(1);
            assertThat(service.checkout(request.id()).reviewAvailable()).isTrue();
            clock.now = end;
            assertThat(current(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
            assertThat(current(request.id()).reviewGrant().eventId()).isEqualTo(event.eventId());
            fails(() -> service.confirm(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
            fails(() -> service.beginVerification(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
        }
    }

    @Test void verifyBeforeReviewDeadlineDoesNotReserveExtraConfirmationTime() {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        Instant end = current(request.id()).reviewGrant().reviewExpiresAt();
        clock.now = end.minusNanos(1); service.beginVerification(request.id(), yes(event));
        clock.now = end; fails(() -> service.confirm(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
        assertThat(current(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
    }

    @Test void reviewDeadlineAndAdmissionSurviveRetryRestartAndConfigurationChange() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); Observation original = ingest(event);
        Instant end = current(request.id()).reviewGrant().reviewExpiresAt();
        clock.now = START.plusSeconds(700); assertThat(ingest(event)).isEqualTo(original);
        restart(300);
        assertThat(ingest(event)).isEqualTo(original);
        assertThat(current(request.id()).reviewGrant().reviewExpiresAt()).isEqualTo(end);
        service.beginVerification(request.id(), yes(event));
        clock.now = end; restart(1800);
        assertThat(current(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
        assertThat(ingest(event)).isEqualTo(original);
        assertThat(current(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
    }

    @Test void reviewExpiredHistorySurvivesLaterInvalidationAndRestart() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        clock.now = request.expiresAt().plusSeconds(1800); service.view();
        service.invalidate(event.eventId(), new Invalidation("NOTIFICATION_CHANGED")); restart(1800);
        assertThat(current(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
        fails(() -> service.confirm(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void missingWrongIdentityAndMissingAttestationCannotVerifyOrConfirm() {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        for (Confirmation input : List.of(new Confirmation(true), new Confirmation(true, UUID.randomUUID()))) {
            fails(() -> service.beginVerification(request.id(), input), "VERIFICATION_NOT_ELIGIBLE");
            fails(() -> service.confirm(request.id(), input), "VERIFICATION_NOT_ELIGIBLE");
        }
        fails(() -> service.beginVerification(request.id(), new Confirmation(false, event.eventId())), "HISTORY_CHECK_REQUIRED");
        service.beginVerification(request.id(), yes(event));
        fails(() -> service.confirm(request.id(), new Confirmation(false, event.eventId())), "HISTORY_CHECK_REQUIRED");
        assertThat(current(request.id()).status()).isEqualTo(Status.AWAITING_VERIFICATION);
    }

    @Test void confirmedSameIdentityRetriesSurviveDeadlineAndEvidenceInvalidation() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        service.beginVerification(request.id(), yes(event)); service.confirm(request.id(), yes(event));
        service.invalidate(event.eventId(), new Invalidation("NOTIFICATION_CHANGED"));
        clock.now = START.plusSeconds(5000); restart(300); service.view();
        byte[] previous = Files.readAllBytes(file);
        assertThat(service.confirm(request.id(), yes(event)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.beginVerification(request.id(), yes(event)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        fails(() -> service.confirm(request.id(), new Confirmation(true, UUID.randomUUID())), "VERIFICATION_NOT_ELIGIBLE");
        assertThat(Files.readAllBytes(file)).isEqualTo(previous);
    }

    @Test void sameAmountHoldPersistsThroughRevocationUntilOriginalReviewEnd() {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        Instant end = current(request.id()).reviewGrant().reviewExpiresAt();
        fails(() -> create(1234), "SAME_AMOUNT_HELD"); assertThat(create(1235).amountMinor()).isEqualTo(1235);
        service.invalidate(event.eventId(), new Invalidation("NOTIFICATION_CHANGED"));
        clock.now = request.expiresAt();
        assertThat(current(request.id()).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(current(request.id()).reviewGrant().revoked()).isTrue();
        fails(() -> create(1234), "SAME_AMOUNT_HELD");
        clock.now = end; assertThat(create(1234).status()).isEqualTo(Status.AWAITING_PAYMENT);
        assertThat(current(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
    }

    @Test void lateAndCrossSourceCompetitorsPermanentlyRevokeWithoutReplacement() {
        for (Source source : Source.values()) {
            int amount = 2000 + source.ordinal(); PaymentRequest request = create(amount);
            Candidate event = candidate(amount, Source.SYNTHETIC); ingest(event); service.beginVerification(request.id(), yes(event));
            clock.now = request.expiresAt().plusSeconds(1);
            Candidate competing = candidate(amount, source); Observation conflict = ingest(competing);
            assertThat(conflict.requestId()).isNull();
            PaymentRequest ambiguous = current(request.id());
            assertThat(ambiguous.status()).isEqualTo(Status.AMBIGUOUS);
            assertThat(ambiguous.observationId()).isNull(); assertThat(ambiguous.reviewGrant().revoked()).isTrue();
            fails(() -> service.confirm(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
            fails(() -> service.resolve(competing.eventId(), new Resolution(request.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        }
    }

    @Test void delayedSameAmountEvidenceAlsoRevokesExistingReview() {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        Candidate delayed = candidate(1234, Source.SYNTHETIC); clock.now = START.plusSeconds(700);
        Observation result = ingest(delayed);
        assertThat(result.requestId()).isNull();
        assertThat(current(request.id()).reviewGrant().revoked()).isTrue();
        fails(() -> service.beginVerification(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void revokedGrantCannotResolveEvenBeforePaymentDeadline() {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        Candidate other = candidate(1234, Source.SYNTHETIC); ingest(other);
        for (Candidate evidence : List.of(event, other)) fails(() -> service.resolve(evidence.eventId(), new Resolution(request.id(), true)), "RESOLUTION_NOT_ELIGIBLE");
        assertThat(current(request.id()).reviewGrant().revoked()).isTrue();
    }

    @Test void lifecycleUpdateAndInvalidationAfterPaymentClosureCannotRestoreGrant() {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        clock.now = request.expiresAt().plusSeconds(1);
        Candidate update = new Candidate(UUID.randomUUID(), event.notificationId(), 2, clock.now, 1234, event.source(), DEVICE,
                event.classification(), event.classifierProfile(), event.evidenceQuality());
        assertThat(ingest(update).disposition()).isEqualTo(Disposition.UPDATE_IGNORED);
        service.invalidate(event.eventId(), new Invalidation("NOTIFICATION_CHANGED"));
        assertThat(current(request.id()).reviewGrant().revoked()).isTrue();
        assertThat(service.checkout(request.id()).reviewAvailable()).isFalse();
    }

    @Test void closedPaymentLatchSurvivesRollbackAndRestart() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        clock.now = request.expiresAt(); assertThat(current(request.id()).paymentClosed()).isTrue();
        clock.now = START; restart(1800);
        assertThat(service.checkout(request.id()).paymentWindowClosed()).isTrue();
        Candidate competing = candidate(1234, Source.SYNTHETIC); assertThat(ingest(competing).requestId()).isNull();
        assertThat(current(request.id()).reviewGrant().revoked()).isTrue();
    }

    @Test void concurrentCreatesOfSameAmountHaveExactlyOneWinner() throws Exception {
        CyclicBarrier start = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); try { create(1234); return "CREATED"; } catch (BridgeFailure e) { return e.code; } });
            var two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); try { create(1234); return "CREATED"; } catch (BridgeFailure e) { return e.code; } });
            start.await(5, TimeUnit.SECONDS);
            assertThat(List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder("CREATED", "SAME_AMOUNT_HELD");
        }
        assertThat(service.view().requests()).hasSize(1);
    }

    @Test void simultaneousDuplicateAdmissionCreatesOneGrantAndConcurrentConfirmationIsIdempotent() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC);
        CyclicBarrier start = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return ingest(event); });
            var two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return ingest(event); });
            start.await(5, TimeUnit.SECONDS); assertThat(one.get(5, TimeUnit.SECONDS)).isEqualTo(two.get(5, TimeUnit.SECONDS));
        }
        assertThat(service.view().observations()).hasSize(1); service.beginVerification(request.id(), yes(event));
        CyclicBarrier confirm = new CyclicBarrier(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { confirm.await(5, TimeUnit.SECONDS); return service.confirm(request.id(), yes(event)); });
            var two = pool.submit(() -> { confirm.await(5, TimeUnit.SECONDS); return service.confirm(request.id(), yes(event)); });
            confirm.await(5, TimeUnit.SECONDS); assertThat(one.get(5, TimeUnit.SECONDS)).isEqualTo(two.get(5, TimeUnit.SECONDS));
        }
        assertThat(current(request.id()).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void concurrentConflictAndConfirmationSerializeToOnlyPermittedOutcomes() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event); service.beginVerification(request.id(), yes(event));
        Candidate competitor = candidate(1234, Source.SYNTHETIC); CyclicBarrier start = new CyclicBarrier(3);
        String outcome;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var confirm = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); try { service.confirm(request.id(), yes(event)); return "CONFIRMED"; } catch (BridgeFailure e) { return e.code; } });
            var conflict = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return ingest(competitor); });
            start.await(5, TimeUnit.SECONDS); outcome = confirm.get(5, TimeUnit.SECONDS); conflict.get(5, TimeUnit.SECONDS);
        }
        if (outcome.equals("CONFIRMED")) assertThat(current(request.id()).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        else { assertThat(outcome).isEqualTo("VERIFICATION_NOT_ELIGIBLE"); assertThat(current(request.id()).status()).isEqualTo(Status.AMBIGUOUS); }
        assertThat(service.view().observations()).hasSize(2);
        assertThat(mapper.readValue(file.toFile(), Snapshot.class)).isEqualTo(service.view());
    }

    @Test void failedAdmissionWriteCannotBackdateRetryAfterDeadline() throws Exception {
        PaymentRequest request = create(1234); clock.now = request.expiresAt().minusSeconds(1); Candidate event = candidate(1234, Source.SYNTHETIC);
        Files.delete(file); Files.createDirectory(file);
        fails(() -> ingest(event), "STORE_UNAVAILABLE"); assertThat(service.view().observations()).isEmpty();
        Files.delete(file); clock.now = request.expiresAt();
        assertThat(ingest(event).requestId()).isNull();
        assertThat(current(request.id()).reviewGrant()).isNull();
    }

    @Test void successfulWriteCompletingAfterDeadlineRetainsItsEarlierSerializedDecision() throws Exception {
        class BoundaryMapper extends ObjectMapper {
            boolean armed;
            BoundaryMapper(ObjectMapper previous) { super(previous); }
            @Override public byte[] writeValueAsBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                byte[] bytes = super.writeValueAsBytes(value);
                if (armed) { clock.now = START.plusSeconds(600); armed = false; }
                return bytes;
            }
        }
        store.close(); BoundaryMapper boundaryMapper = new BoundaryMapper(mapper);
        mapper = boundaryMapper; store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE, 1800);
        PaymentRequest request = create(1234); clock.now = request.expiresAt().minusNanos(1);
        Candidate event = candidate(1234, Source.SYNTHETIC); boundaryMapper.armed = true;
        Observation accepted = ingest(event);
        assertThat(clock.now).isEqualTo(request.expiresAt());
        assertThat(accepted.acceptedAt()).isEqualTo(request.expiresAt().minusNanos(1));
        assertThat(current(request.id()).reviewGrant()).isNotNull();
        service.beginVerification(request.id(), yes(event));
        assertThat(service.confirm(request.id(), yes(event)).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void delayedEvidenceBeforeFreshCandidateCannotBecomeCertaintyAfterRestart() throws Exception {
        PaymentRequest request = create(1234); Candidate delayed = candidate(1234, Source.SYNTHETIC);
        clock.now = START.plusSeconds(121); assertThat(ingest(delayed).disposition()).isEqualTo(Disposition.DELAYED);
        Candidate fresh = candidate(1234, Source.SYNTHETIC);
        assertThat(ingest(fresh).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(current(request.id()).reviewGrant()).isNull(); restart(1800);
        assertThat(current(request.id()).status()).isEqualTo(Status.AMBIGUOUS);
        fails(() -> service.beginVerification(request.id(), yes(fresh)), "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void mixedLegacySnapshotsPreserveTenMinuteBehaviorAndNeverReviveExpiredMatchedHistory() throws Exception {
        PaymentRequest modern = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        PaymentRequest legacy = new PaymentRequest(UUID.randomUUID(), 1235, START, START.plusSeconds(600), Status.AWAITING_VERIFICATION, UUID.randomUUID());
        Candidate historical = new Candidate(legacy.observationId(), UUID.randomUUID(), 1, START, 1235, Source.SIMULATED);
        Snapshot state = service.view();
        store.save(new Snapshot(List.of(state.requests().getFirst(), legacy), List.of(state.observations().getFirst(), new Observation(historical, Disposition.MATCHED, legacy.id()))));
        restart(1800); clock.now = START.plusSeconds(600);
        assertThat(current(modern.id()).status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(current(legacy.id()).status()).isEqualTo(Status.EXPIRED);
        assertThat(current(legacy.id()).reviewGrant()).isNull();
        restart(1800); assertThat(current(legacy.id()).status()).isEqualTo(Status.EXPIRED);
    }

    @Test void malformedVersionTwoGrantGraphFailsClosedWithoutRepair() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        byte[] valid = Files.readAllBytes(file);
        for (String corruption : List.of("event", "deadline", "admission", "association", "version", "revoked", "stale", "competitor", "historicalcompetitor")) {
            ObjectNode root = (ObjectNode) mapper.readTree(valid);
            ObjectNode saved = (ObjectNode) root.path("requests").get(0);
            ObjectNode observation = (ObjectNode) root.path("observations").get(0);
            switch (corruption) {
                case "event" -> ((ObjectNode) saved.path("reviewGrant")).put("eventId", UUID.randomUUID().toString());
                case "deadline" -> ((ObjectNode) saved.path("reviewGrant")).put("reviewExpiresAt", request.expiresAt().plusSeconds(1801).toString());
                case "admission" -> observation.remove("acceptedAt");
                case "association" -> observation.put("requestId", UUID.randomUUID().toString());
                case "version" -> saved.put("lifecycleVersion", 3);
                case "revoked" -> ((ObjectNode) saved.path("reviewGrant")).put("revoked", true);
                case "stale" -> observation.put("acceptedAt", START.plusSeconds(121).toString());
                case "competitor" -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("observations"))
                        .add(mapper.valueToTree(new Observation(candidate(1234, Source.SYNTHETIC), Disposition.UNMATCHED, null, START.plusSeconds(1))));
                case "historicalcompetitor" -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("observations"))
                        .add(mapper.valueToTree(new Observation(candidate(1234, Source.SYNTHETIC), Disposition.UNMATCHED, null)));
                default -> throw new AssertionError();
            }
            Files.write(file, mapper.writeValueAsBytes(root));
            assertThatThrownBy(() -> new ObservationService(store, clock, DEVICE, 1800)).as(corruption).isInstanceOf(IllegalStateException.class).hasMessage("Invalid experimental store");
        }
        Files.write(file, valid);
    }

    @Test void manuallyResolvedVersionTwoEvidenceHasNoGrantAndExpiresAtPaymentDeadline() throws Exception {
        PaymentRequest request = create(1234);
        PaymentRequest legacy = new PaymentRequest(UUID.randomUUID(), 1234, START, START.plusSeconds(600), Status.AWAITING_PAYMENT, null);
        store.save(new Snapshot(List.of(request, legacy), List.of())); restart(1800);
        Candidate event = candidate(1234, Source.SYNTHETIC);
        assertThat(ingest(event).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.resolve(event.eventId(), new Resolution(request.id(), true)).disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(current(request.id()).reviewGrant()).isNull(); restart(1800);
        service.beginVerification(request.id(), yes(event));
        clock.now = request.expiresAt();
        fails(() -> service.confirm(request.id(), yes(event)), "VERIFICATION_NOT_ELIGIBLE");
        assertThat(current(request.id()).status()).isEqualTo(Status.EXPIRED);
    }

    @Test void malformedManuallyResolvedFreshnessCannotBypassVersionTwoStartupValidation() {
        PaymentRequest request = create(1234); Instant admitted = START.plusSeconds(150);
        for (Instant observed : List.of(admitted.minusSeconds(121), admitted.plusSeconds(31))) {
            Candidate event = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observed, 1234, Source.SYNTHETIC,
                    DEVICE, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
            PaymentRequest resolved = new PaymentRequest(request.id(), request.amountMinor(), request.createdAt(), request.expiresAt(),
                    Status.PAYMENT_DETECTED, event.eventId(), 2, false, null);
            store.save(new Snapshot(List.of(resolved), List.of(new Observation(event, Disposition.MATCHED, request.id(), admitted))));
            assertThatThrownBy(() -> new ObservationService(store, clock, DEVICE, 1800)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Invalid experimental store");
        }
    }

    @Test void countCapacityCannotLeaveAConfirmableGrantAndSurvivesRestart() throws Exception {
        PaymentRequest request = create(1234); Candidate event = candidate(1234, Source.SYNTHETIC); ingest(event);
        service.beginVerification(request.id(), yes(event));
        Snapshot previous = service.view(); var observations = new ArrayList<>(previous.observations());
        for (int i = 1; i < ObservationService.MAX_OBSERVATIONS; i++) observations.add(new Observation(candidate(9999, Source.SYNTHETIC), Disposition.UNMATCHED, null, clock.now));
        store.save(new Snapshot(previous.requests(), observations)); restart(1800);
        fails(() -> service.confirm(request.id(), yes(event)), "EVIDENCE_CAPACITY");
        try { ingest(candidate(1234, Source.SYNTHETIC)); } catch (BridgeFailure failure) { assertThat(failure.code).isIn("OBSERVATION_CAPACITY", "EVIDENCE_CAPACITY"); }
        restart(1800);
        assertThatThrownBy(() -> service.confirm(request.id(), yes(event))).isInstanceOf(BridgeFailure.class);
        assertThat(current(request.id()).status()).isNotEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.view().observations()).hasSize(ObservationService.MAX_OBSERVATIONS);
    }

    @Test void boundedSchemaAtMaximumCountsFitsByteCapAndHeadroomCoversMassRevocation() {
        Instant far = Instant.parse("+999999999-12-31T22:00:00Z");
        List<PaymentRequest> requests = new ArrayList<>(); List<Observation> observations = new ArrayList<>();
        for (int i = 0; i < ObservationService.MAX_OBSERVATIONS; i++) {
            UUID eventId = UUID.randomUUID(); UUID requestId = UUID.randomUUID();
            Candidate event = new Candidate(eventId, UUID.randomUUID(), 1_000_000, far, 1_000_000, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                    "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
            observations.add(new Observation(event, Disposition.MATCHED, requestId, far));
            if (i < ObservationService.MAX_REQUESTS) requests.add(new PaymentRequest(requestId, 1_000_000, far, far.plusSeconds(600), Status.AWAITING_VERIFICATION,
                    eventId, 2, false, new ReviewGrant(eventId, far.plusSeconds(2400), false)));
        }
        Snapshot maximum = new Snapshot(requests, observations); int before = store.serializedSize(maximum);
        // Structural upper bound, not a claim this deliberately overlapping graph can pass loader validation.
        assertThat((long) before + SnapshotStore.EVIDENCE_HEADROOM).isLessThan(SnapshotStore.MAX_BYTES);
        List<PaymentRequest> revoked = requests.stream().map(r -> new PaymentRequest(r.id(), r.amountMinor(), r.createdAt(), r.expiresAt(), Status.AMBIGUOUS,
                null, 2, true, new ReviewGrant(r.reviewGrant().eventId(), r.reviewGrant().reviewExpiresAt(), true))).toList();
        var changed = new ArrayList<>(observations.stream().map(o -> new Observation(o.candidate(), Disposition.INVALIDATED, o.requestId(), o.acceptedAt())).toList());
        changed.add(new Observation(observations.getFirst().candidate(), Disposition.AMBIGUOUS, null, far));
        int delta = store.serializedSize(new Snapshot(revoked, changed)) - before;
        assertThat(delta).isLessThan(SnapshotStore.EVIDENCE_HEADROOM);
        // Ignore space reclaimed by clearing 500 selections: positive-only worst growth must also fit.
        int positiveGrowth = store.serializedSize(new Snapshot(requests, changed)) - before;
        assertThat(positiveGrowth).isBetween(1, SnapshotStore.EVIDENCE_HEADROOM - 1);
        assertThat(mapper.valueToTree(changed.getLast()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThan(4096);
        assertThat(SnapshotStore.EVIDENCE_HEADROOM).isEqualTo(8192);
        System.out.println("B5_CAPACITY_BOUND maximumShapeBytes=" + before + " positiveMutationGrowth=" + positiveGrowth + " reserve=" + SnapshotStore.EVIDENCE_HEADROOM);
    }

    @Test void reviewPeriodBoundsRejectInvalidStartupConfiguration() {
        for (int seconds : new int[]{-1, 0, 299, 1801, Integer.MAX_VALUE})
            assertThatThrownBy(() -> new ObservationService(store, clock, DEVICE, seconds)).isInstanceOf(IllegalStateException.class);
        assertThat(new ObservationService(store, clock, DEVICE, 300)).isNotNull();
        assertThat(new ObservationService(store, clock, DEVICE, 1800)).isNotNull();
    }

    static final class TestClock extends Clock {
        volatile Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
