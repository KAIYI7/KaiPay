package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Multi-checkout observed mode at service level: a configured total of single-use checkouts per store with unpinned
 * amounts, the count refusal, the generalised admission gate and the startup store guards. Single-checkout observed
 * mode is covered, unchanged, by {@link ObservedModeServiceTest}.
 */
class ObservedMultiCheckoutServiceTest {
    static final UUID DEVICE = NotificationCandidateTest.DEVICE_ID;
    static final Instant START = LifecycleTest.START;
    static final int COUNT = 3;
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    LifecycleTest.TestClock clock;
    Path file;

    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        clock = new LifecycleTest.TestClock(); file = directory.resolve("state.json");
        store = new SnapshotStore(file, mapper); service = multi(COUNT);
    }
    @AfterEach void close() throws Exception { store.close(); }
    ObservationService multi(Integer count) { return new ObservationService(store, clock, DEVICE, 1800, true, null, count); }
    /** Reopens the same store with the given pinned amount and count (both null: synthetic integration or non-integration). */
    ObservationService reopen(Integer amount, Integer count, boolean integration) {
        try { store.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        store = new SnapshotStore(file, mapper);
        return new ObservationService(store, clock, DEVICE, 1800, integration, amount, count);
    }
    void refusedOn(Integer amount, Integer count, boolean integration, String message) {
        assertThatThrownBy(() -> reopen(amount, count, integration)).isInstanceOf(IllegalStateException.class).hasMessage(message);
    }
    Candidate genuine(int amount, Instant observedAt) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
    }
    Candidate kaching(int amount, Instant observedAt) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.kaching.en.observed.v1", "RULE_MATCHED");
    }
    static void fails(Runnable action, int status, String code) { IntegrationServiceTest.fails(action, status, code); }
    byte[] bytes() throws Exception { return Files.exists(file) ? Files.readAllBytes(file) : null; }
    /** Refused with no state change: snapshot bytes (or their absence) and the in-memory view are unchanged. view() publishes any
     *  due lazy expiry first, so this cannot see a refusal that publishes it; spentRefusalWritesNothingEvenWhenLazyExpiryIsDue and
     *  the t=950 block of gateUsesEachCheckoutsOwnHoldIncludingReviewAndAmbiguousHolds cover that with raw bytes. */
    void refusedUnchanged(Runnable action, int status, String code) throws Exception {
        byte[] before = bytes(); Snapshot view = service.view(); before = bytes();
        fails(action, status, code);
        assertThat(bytes()).isEqualTo(before);
        assertThat(service.view()).isEqualTo(view);
    }
    IntegrationCheckout created(UUID reference, int amount) {
        ObservationService.IntegrationPut put = service.putIntegration(reference, amount);
        assertThat(put.created()).isTrue();
        assertThat(put.checkout().evidenceMode()).isEqualTo(EvidenceMode.OBSERVED_NOTIFICATION);
        return put.checkout();
    }
    void confirm(UUID id, UUID event) {
        service.beginVerification(id, new Confirmation(true, event)); service.confirm(id, new Confirmation(true, event));
    }
    static PaymentRequest stored(int amount, Status status) {
        return new PaymentRequest(UUID.randomUUID(), amount, START, START.plusSeconds(600), status, null, 2, false, null, 1,
                UUID.randomUUID(), EvidenceMode.OBSERVED_NOTIFICATION);
    }

    // ---- Startup ---------------------------------------------------------------------------------------------------

    @Test void countModeNeedsIntegrationALiveDeviceAPositiveCountAndNoPinnedAmount() {
        for (Integer count : new Integer[]{0, -1, Integer.MIN_VALUE})
            refusedOn(null, count, true, "Invalid observed mode");
        refusedOn(null, 2, false, "Invalid observed mode");      // not integration
        refusedOn(1, 2, true, "Invalid observed mode");          // both settings
        refusedOn(1, 1, true, "Invalid observed mode");
        assertThatThrownBy(() -> { try { store.close(); } catch (Exception e) { throw new IllegalStateException(e); }
            store = new SnapshotStore(file, mapper); new ObservationService(store, clock, null, 1800, true, null, 2); })
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid observed mode");
        // No compiled maximum on the count.
        assertThat(reopen(null, 1, true).view().requests()).isEmpty();
        assertThat(reopen(null, 999_999_999, true).view().requests()).isEmpty();
        assertThat(reopen(null, Integer.MAX_VALUE, true).view().requests()).isEmpty();
        // The single-checkout constructor is unchanged.
        assertThat(reopen(1, null, true).view().requests()).isEmpty();
        refusedOn(101, null, true, "Invalid observed mode");
    }

    @Test void storeWithUpToTheCountOfObservedCheckoutsAtAnyAmountsRestartsAndOneMoreIsRefused() throws Exception {
        String refused = "Observed mode requires an empty or observed-mode store";
        List<PaymentRequest> three = List.of(stored(863, Status.AWAITING_PAYMENT), stored(5_000, Status.EXPIRED), stored(1_000_000, Status.AWAITING_PAYMENT));
        // Two at 863 and 1,000,000 are open; the EXPIRED one is terminal.
        List<PaymentRequest> valid = List.of(three.get(0), three.get(1).changed(Status.EXPIRED, null, true, null), three.get(2));
        store.save(new Snapshot(valid, List.of(new Observation(genuine(700, START), Disposition.UNMATCHED, null, START))));
        assertThat(reopen(null, 3, true).view().requests()).hasSize(3);
        assertThat(reopen(null, 4, true).view().requests()).hasSize(3);
        refusedOn(null, 2, true, refused);                  // more checkouts than this process's count
        refusedOn(1, null, true, refused);                  // single-checkout mode: more than one, and other amounts
        refusedOn(null, null, true, "Observed-mode store requires observed mode");
        refusedOn(null, null, false, "Observed-mode store requires observed mode");
        // A one-checkout store at an unpinned amount is refused by single-checkout mode at any other amount.
        store.save(new Snapshot(List.of(stored(50, Status.AWAITING_PAYMENT)), List.of()));
        refusedOn(1, null, true, refused);
        assertThat(reopen(50, null, true).view().requests()).hasSize(1);
        assertThat(reopen(null, 1, true).view().requests()).hasSize(1);
    }

    @Test void countModeRefusesEveryOtherKindOfStoreContent() throws Exception {
        String refused = "Observed mode requires an empty or observed-mode store";
        PaymentRequest observed = stored(863, Status.AWAITING_PAYMENT);
        PaymentRequest synthetic = new PaymentRequest(UUID.randomUUID(), 864, START, START.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1, UUID.randomUUID());
        PaymentRequest receiver = new PaymentRequest(UUID.randomUUID(), 865, START, START.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1, null);
        Observation simulated = new Observation(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, START, 5, Source.SIMULATED), Disposition.UNMATCHED, null, START);
        Observation syntheticSeen = new Observation(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, START, 5, Source.SYNTHETIC, DEVICE,
                "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC"), Disposition.UNMATCHED, null, START);
        Observation genuineSeen = new Observation(genuine(5, START), Disposition.UNMATCHED, null, START);
        record Case(List<PaymentRequest> requests, List<Observation> observations) {}
        for (Case invalid : List.of(new Case(List.of(synthetic), List.of()), new Case(List.of(receiver), List.of()),
                new Case(List.of(observed, synthetic), List.of()), new Case(List.of(observed, receiver), List.of()),
                new Case(List.of(observed), List.of(simulated)), new Case(List.of(observed), List.of(syntheticSeen)),
                new Case(List.of(), List.of(genuineSeen)), new Case(List.of(), List.of(simulated)))) {
            store.save(new Snapshot(invalid.requests(), invalid.observations()));
            byte[] before = bytes();
            refusedOn(null, COUNT, true, refused);
            assertThat(bytes()).isEqualTo(before);
        }
        store.save(new Snapshot(List.of(observed), List.of(genuineSeen)));
        assertThat(reopen(null, COUNT, true).view().requests()).hasSize(1);
    }

    @Test void genuineEvidenceOnAnUnpinnedBindingLoadsInCountModeOnly() throws Exception {
        UUID reference = UUID.randomUUID();
        IntegrationCheckout checkout = created(reference, 863);
        clock.now = START.plusSeconds(20);
        assertThat(service.ingestNotification(genuine(863, clock.now)).disposition()).isEqualTo(Disposition.MATCHED);
        byte[] stored = bytes();
        service = reopen(null, COUNT, true);
        IntegrationCheckout restored = service.integrationCheckout(reference);
        assertThat(restored.status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(restored.evidence()).isEqualTo(PayerEvidence.NOTIFICATION_UNVERIFIED);
        assertThat(restored.checkoutId()).isEqualTo(checkout.checkoutId());
        assertThat(reopen(null, 1, true).integrationCheckout(reference).status()).isEqualTo(Status.PAYMENT_DETECTED);
        refusedOn(100, null, true, "Invalid experimental store");  // genuine evidence on a binding outside the pinned amount
        refusedOn(null, null, true, "Invalid experimental store");
        refusedOn(null, null, false, "Invalid experimental store");
        assertThat(bytes()).isEqualTo(stored);
    }

    // ---- PUT -------------------------------------------------------------------------------------------------------

    @Test void putCreatesUpToTheCountAtUnpinnedAmountsThenRefusesAsSpentWithNoStateChange() throws Exception {
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 0), 422, "AMOUNT_INVALID");
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), ObservationService.MAX_AMOUNT + 1), 422, "AMOUNT_INVALID");
        assertThat(file).doesNotExist();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), third = UUID.randomUUID();
        // Above the single-checkout cap, and the largest valid amount: never OBSERVED_AMOUNT_MISMATCH here.
        IntegrationCheckout a = created(first, 863), b = created(second, ObservationService.MAX_AMOUNT);
        // A held amount is refused without using up the count.
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 863), 409, "SAME_AMOUNT_HELD");
        IntegrationCheckout c = created(third, 1);
        assertThat(List.of(a.amountMinor(), b.amountMinor(), c.amountMinor())).containsExactly(863, ObservationService.MAX_AMOUNT, 1);
        assertThat(service.view().requests()).hasSize(COUNT).allSatisfy(r -> assertThat(r.evidenceMode()).isEqualTo(EvidenceMode.OBSERVED_NOTIFICATION));
        // Spent comes before the hold check: a held amount and a free one are both refused as spent.
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 2), 409, "OBSERVED_CHECKOUT_SPENT");
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 863), 409, "OBSERVED_CHECKOUT_SPENT");
        // Replay and conflict of a bound reference: the lookup still comes first.
        ObservationService.IntegrationPut replay = service.putIntegration(second, ObservationService.MAX_AMOUNT);
        assertThat(replay.created()).isFalse();
        assertThat(replay.checkout()).isEqualTo(b);
        refusedUnchanged(() -> service.putIntegration(first, 864), 409, "REFERENCE_CONFLICT");
        // Single use: still spent once every checkout has ended, and after a restart.
        clock.now = START.plusSeconds(600);
        assertThat(service.integrationCheckout(first).status()).isEqualTo(Status.EXPIRED);
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 2), 409, "OBSERVED_CHECKOUT_SPENT");
        service = reopen(null, COUNT, true);
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 2), 409, "OBSERVED_CHECKOUT_SPENT");
        assertThat(service.putIntegration(third, 1).created()).isFalse();
        assertThat(service.view().requests()).hasSize(COUNT);
        // A process restarted with a larger count may create more on the same store.
        service = reopen(null, COUNT + 1, true);
        assertThat(service.putIntegration(UUID.randomUUID(), 863).created()).isTrue();
    }

    @Test void spentRefusalWritesNothingEvenWhenLazyExpiryIsDue() throws Exception {
        service = reopen(null, 1, true);
        UUID reference = UUID.randomUUID();
        created(reference, 863);
        clock.now = START.plusSeconds(600);
        byte[] before = bytes();
        fails(() -> service.putIntegration(UUID.randomUUID(), 864), 409, "OBSERVED_CHECKOUT_SPENT");
        assertThat(bytes()).isEqualTo(before);
        assertThat(mapper.readTree(file.toFile()).get("requests").get(0).get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(service.putIntegration(reference, 863).checkout().status()).isEqualTo(Status.EXPIRED);
    }

    @Test void heldAmountFreesAfterItsHoldEndsWithinTheCount() throws Exception {
        UUID first = UUID.randomUUID();
        IntegrationCheckout checkout = created(first, 863);
        clock.now = START.plusSeconds(20);
        Candidate event = genuine(863, clock.now);
        service.ingestNotification(event);
        clock.now = START.plusSeconds(700); // window closed, unconfirmed review grant: still held
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 863), 409, "SAME_AMOUNT_HELD");
        confirm(checkout.checkoutId(), event.eventId()); // confirmed: no longer held
        IntegrationCheckout again = created(UUID.randomUUID(), 863);
        assertThat(again.checkoutId()).isNotEqualTo(checkout.checkoutId());
        assertThat(again.status()).isEqualTo(Status.AWAITING_PAYMENT);
    }

    @Test void countOneTakesOneCheckoutAtAnyAmount() throws Exception {
        service = reopen(null, 1, true);
        created(UUID.randomUUID(), 863);
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 1), 409, "OBSERVED_CHECKOUT_SPENT");
    }

    @Test void concurrentPutsCreateExactlyTheCountAndEveryOtherIsSpent() throws Exception {
        int count = 5, callers = 16;
        service = reopen(null, count, true);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers), go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                int amount = 1_000 + i; // distinct amounts, so only the count can refuse
                results.add(pool.submit(() -> {
                    ready.countDown(); go.await();
                    try { return service.putIntegration(UUID.randomUUID(), amount).created() ? "CREATED" : "REPLAY"; }
                    catch (BridgeFailure failure) { return failure.status + " " + failure.code; }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("CREATED"::equals)).hasSize(count);
            assertThat(outcomes.stream().filter("409 OBSERVED_CHECKOUT_SPENT"::equals)).hasSize(callers - count);
        } finally { pool.shutdownNow(); }
        assertThat(service.view().requests()).hasSize(count);
        assertThat(reopen(null, count, true).view().requests()).hasSize(count);
    }

    @Test void concurrentSameAmountPutsCreateOneAndHoldTheRest() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers), go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) results.add(pool.submit(() -> {
                ready.countDown(); go.await();
                try { return service.putIntegration(UUID.randomUUID(), 863).created() ? "CREATED" : "REPLAY"; }
                catch (BridgeFailure failure) { return failure.status + " " + failure.code; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("CREATED"::equals)).hasSize(1);
            assertThat(outcomes.stream().filter("409 SAME_AMOUNT_HELD"::equals)).hasSize(callers - 1);
        } finally { pool.shutdownNow(); }
        assertThat(service.view().requests()).hasSize(1);
    }

    // ---- Admission gate -------------------------------------------------------------------------------------------

    @Test void candidateIsAdmittedOnlyForTheAmountOfAHeldNonTerminalCheckoutAndMatchesOnlyThatCheckout() throws Exception {
        clock.now = START;
        refusedUnchanged(() -> service.ingestNotification(genuine(863, clock.now)), 422, "GENUINE_DISABLED"); // no checkout
        UUID ra = UUID.randomUUID(), rb = UUID.randomUUID();
        IntegrationCheckout a = created(ra, 863), b = created(rb, 500);
        clock.now = START.plusSeconds(20);
        for (int other : new int[]{1, 499, 501, 862, 864, 100, ObservationService.MAX_AMOUNT})
            refusedUnchanged(() -> service.ingestNotification(genuine(other, clock.now)), 422, "GENUINE_DISABLED");
        Candidate forA = genuine(863, clock.now);
        Observation matched = service.ingestNotification(forA);
        assertThat(matched.disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(matched.requestId()).isEqualTo(a.checkoutId());
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(service.integrationCheckout(ra).evidence()).isEqualTo(PayerEvidence.NOTIFICATION_UNVERIFIED);
        assertThat(service.integrationCheckout(rb)).isEqualTo(b); // untouched, same revision
        confirm(a.checkoutId(), forA.eventId());
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        // a is terminal: its amount is refused even though b is still held at another amount.
        refusedUnchanged(() -> service.ingestNotification(genuine(863, clock.now)), 422, "GENUINE_DISABLED");
        Candidate forB = kaching(500, clock.now);
        Observation second = service.ingestNotification(forB);
        assertThat(second.disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(second.requestId()).isEqualTo(b.checkoutId());
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.view().observations()).hasSize(2);
    }

    @Test void sameAmountSecondNotificationMakesOnlyThatCheckoutAmbiguous() throws Exception {
        UUID ra = UUID.randomUUID(), rb = UUID.randomUUID();
        created(ra, 863); created(rb, 500);
        clock.now = START.plusSeconds(20);
        service.ingestNotification(genuine(863, clock.now));
        Candidate forB = genuine(500, clock.now);
        service.ingestNotification(forB);
        clock.now = START.plusSeconds(30);
        assertThat(service.ingestNotification(genuine(863, clock.now)).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(service.integrationCheckout(rb).status()).isEqualTo(Status.PAYMENT_DETECTED);
        confirm(service.integrationCheckout(rb).checkoutId(), forB.eventId());
        assertThat(service.integrationCheckout(rb).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void gateUsesEachCheckoutsOwnHoldIncludingReviewAndAmbiguousHolds() throws Exception {
        UUID ra = UUID.randomUUID();
        created(ra, 863);
        clock.now = START.plusSeconds(300);
        UUID rb = UUID.randomUUID();
        created(rb, 500); // window to START + 900
        clock.now = START.plusSeconds(310);
        service.ingestNotification(genuine(863, clock.now)); // a matched: review hold to START + 600 + 1800
        clock.now = START.plusSeconds(700); // a's window closed (review hold running), b's window still open
        Observation late = service.ingestNotification(genuine(863, clock.now));
        assertThat(late.disposition()).isEqualTo(Disposition.EXPIRED);
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.AMBIGUOUS); // revoked grant: still held
        clock.now = START.plusSeconds(950); // b's window closed with no grant: b is no longer held
        byte[] before = bytes();
        fails(() -> service.ingestNotification(genuine(500, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(bytes()).isEqualTo(before); // no lazy expiry published by the refusal
        Observation third = service.ingestNotification(genuine(863, clock.now)); // a still held (revoked grant)
        assertThat(service.view().observations()).contains(third);
        assertThat(service.integrationCheckout(rb).status()).isEqualTo(Status.EXPIRED);
        clock.now = START.plusSeconds(2400); // a's review deadline: nothing is held
        refusedUnchanged(() -> service.ingestNotification(genuine(863, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.REVIEW_EXPIRED);
    }

    @Test void exactRetryIsAcknowledgedBeforeTheGateAndAReusedIdIsRefused() throws Exception {
        UUID ra = UUID.randomUUID();
        IntegrationCheckout a = created(ra, 863);
        clock.now = START.plusSeconds(20);
        Candidate event = genuine(863, clock.now);
        Observation stored = service.ingestNotification(event);
        confirm(a.checkoutId(), event.eventId());
        clock.now = START.plusSeconds(4000);
        byte[] before = bytes();
        assertThat(service.ingestNotification(event)).isEqualTo(stored);
        assertThat(bytes()).isEqualTo(before);
        Candidate reused = new Candidate(event.eventId(), event.notificationId(), 2, event.observedAt(), 863, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
        refusedUnchanged(() -> service.ingestNotification(reused), 422, "GENUINE_DISABLED");
    }

    @Test void syntheticSourcesReceiverCreateAndResolveStayRefused() throws Exception {
        refusedUnchanged(() -> service.create(new CreateRequest(863)), 409, "RECEIVER_CREATE_DISABLED");
        UUID ra = UUID.randomUUID();
        IntegrationCheckout a = created(ra, 863);
        clock.now = START.plusSeconds(5);
        refusedUnchanged(() -> service.ingest(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 863, Source.SIMULATED)), 422, "SYNTHETIC_DISABLED");
        refusedUnchanged(() -> service.ingestNotification(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 863, Source.SYNTHETIC, DEVICE,
                "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC")), 422, "SYNTHETIC_DISABLED");
        // An unmatched candidate admitted after creation competes, so the next one is AMBIGUOUS with no grant; resolve refuses.
        assertThat(service.ingestNotification(genuine(863, START.minusSeconds(5))).disposition()).isEqualTo(Disposition.UNMATCHED);
        clock.now = START.plusSeconds(10);
        Observation ambiguous = service.ingestNotification(genuine(863, clock.now));
        assertThat(ambiguous.disposition()).isEqualTo(Disposition.AMBIGUOUS);
        refusedUnchanged(() -> service.resolve(ambiguous.candidate().eventId(), new Resolution(a.checkoutId(), true)), 409, "RESOLUTION_NOT_ELIGIBLE");
    }

    @Test void lateUpdateOfAConfirmedCheckoutsNotificationNeverMatchesAndFailsTowardsAmbiguous() throws Exception {
        // Documented fail-safe side effect: B reuses A's amount after A is confirmed and its window ends; a late update of
        // A's notification is admitted because B holds the amount, is never matched, and then competes with B's own notification.
        UUID ra = UUID.randomUUID(), rb = UUID.randomUUID();
        IntegrationCheckout a = created(ra, 863);
        clock.now = START.plusSeconds(20);
        Candidate original = genuine(863, clock.now);
        service.ingestNotification(original);
        confirm(a.checkoutId(), original.eventId());
        clock.now = START.plusSeconds(700);
        IntegrationCheckout b = created(rb, 863);
        clock.now = START.plusSeconds(710);
        Candidate update = new Candidate(UUID.randomUUID(), original.notificationId(), 2, clock.now, 863, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
        Observation ignored = service.ingestNotification(update);
        assertThat(ignored.disposition()).isEqualTo(Disposition.UPDATE_IGNORED);
        assertThat(ignored.requestId()).isEqualTo(a.checkoutId());
        assertThat(service.integrationCheckout(rb).status()).isEqualTo(Status.AWAITING_PAYMENT);
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        // A stays confirmed; only its original observation is marked AMBIGUOUS, which the receiver page then shows.
        assertThat(service.view().observations().stream().filter(o -> o.candidate().eventId().equals(original.eventId())).findFirst().orElseThrow()
                .disposition()).isEqualTo(Disposition.AMBIGUOUS);
        clock.now = START.plusSeconds(720);
        Observation forB = service.ingestNotification(genuine(863, clock.now));
        assertThat(forB.disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.integrationCheckout(rb).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(service.integrationCheckout(rb).checkoutId()).isEqualTo(b.checkoutId());
        service = reopen(null, COUNT, true); // the resulting store still loads
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.integrationCheckout(rb).status()).isEqualTo(Status.AMBIGUOUS);
    }

    @Test void lateUploadIsDelayedAndNeverMatches() throws Exception {
        UUID ra = UUID.randomUUID();
        created(ra, 863);
        clock.now = START.plusSeconds(200);
        Observation delayed = service.ingestNotification(genuine(863, START.plusSeconds(60)));
        assertThat(delayed.disposition()).isEqualTo(Disposition.DELAYED);
        assertThat(service.integrationCheckout(ra).status()).isEqualTo(Status.AWAITING_PAYMENT);
    }
}
