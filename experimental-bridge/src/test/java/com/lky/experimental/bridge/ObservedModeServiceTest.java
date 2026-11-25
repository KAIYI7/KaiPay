package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Observed-evidence mode at service level: startup matrix, store guards, admission gate and PUT order. */
class ObservedModeServiceTest {
    static final UUID DEVICE = NotificationCandidateTest.DEVICE_ID;
    static final Instant START = LifecycleTest.START;
    static final int PINNED = 1;
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    LifecycleTest.TestClock clock;
    Path file;

    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        clock = new LifecycleTest.TestClock(); file = directory.resolve("state.json");
        store = new SnapshotStore(file, mapper); service = observed(PINNED);
    }
    @AfterEach void close() throws Exception { store.close(); }
    ObservationService observed(Integer amount) { return new ObservationService(store, clock, DEVICE, 1800, true, amount); }
    /** Reopens the same store as observed (amount), synthetic integration (null, true) or non-integration (null, false). */
    ObservationService reopen(Integer amount, boolean integration) {
        try { store.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        store = new SnapshotStore(file, mapper);
        return new ObservationService(store, clock, DEVICE, 1800, integration, amount);
    }
    void refusedOn(Integer amount, boolean integration, String message) {
        assertThatThrownBy(() -> reopen(amount, integration)).isInstanceOf(IllegalStateException.class).hasMessage(message);
    }
    Candidate genuine(int amount, Instant observedAt) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
    }
    Candidate synthetic(int amount, Instant observedAt) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, Source.SYNTHETIC, DEVICE,
                "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
    }
    static void fails(Runnable action, int status, String code) { IntegrationServiceTest.fails(action, status, code); }
    byte[] bytes() throws Exception { return Files.exists(file) ? Files.readAllBytes(file) : null; }
    /** Refused with no state change: snapshot bytes (or their absence) and the in-memory view are unchanged. */
    void refusedUnchanged(Runnable action, int status, String code) throws Exception {
        byte[] before = bytes(); Snapshot view = service.view(); before = bytes();
        fails(action, status, code);
        assertThat(bytes()).isEqualTo(before);
        assertThat(service.view()).isEqualTo(view);
    }
    IntegrationCheckout created(UUID reference) {
        ObservationService.IntegrationPut put = service.putIntegration(reference, PINNED);
        assertThat(put.created()).isTrue();
        return put.checkout();
    }
    PaymentRequest only() { assertThat(service.view().requests()).hasSize(1); return service.view().requests().getFirst(); }
    void confirm(UUID id, UUID event) {
        service.beginVerification(id, new Confirmation(true, event)); service.confirm(id, new Confirmation(true, event));
    }

    // ---- Startup matrix -------------------------------------------------------------------------------------------

    @Test void observedModeNeedsIntegrationALiveDeviceAndAnAmountWithinTheCompiledCap() {
        assertThat(BridgeAccess.OBSERVED_AMOUNT_CAP).isEqualTo(100);
        for (Integer amount : new Integer[]{0, -1, 101, 1_000_000})
            assertThatThrownBy(() -> reopen(amount, true)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid observed mode");
        assertThatThrownBy(() -> reopen(1, false)).isInstanceOf(IllegalStateException.class).hasMessage("Invalid observed mode");
        assertThatThrownBy(() -> { try { store.close(); } catch (Exception e) { throw new IllegalStateException(e); }
            store = new SnapshotStore(file, mapper); new ObservationService(store, clock, null, 1800, true, 1); })
                .isInstanceOf(IllegalStateException.class).hasMessage("Invalid observed mode");
        assertThat(reopen(100, true).view().requests()).isEmpty();
        assertThat(reopen(1, true).view().requests()).isEmpty();
    }

    @Test void observedStoreHoldingItsOwnCheckoutAndGenuineEvidenceRestartsOnlyInObservedModeAtTheSameAmount() throws Exception {
        UUID reference = UUID.randomUUID();
        IntegrationCheckout checkout = created(reference);
        clock.now = START.plusSeconds(20);
        Observation matched = service.ingestNotification(genuine(PINNED, clock.now));
        assertThat(matched.disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(only().evidenceMode()).isEqualTo(EvidenceMode.OBSERVED_NOTIFICATION);
        assertThat(Files.readString(file)).contains("\"evidenceMode\":\"OBSERVED_NOTIFICATION\"");
        byte[] stored = bytes();
        // Crash restart: same mode and amount keep the binding, the evidence and the grant.
        service = reopen(PINNED, true);
        IntegrationCheckout restored = service.integrationCheckout(reference);
        assertThat(restored.status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(restored.evidence()).isEqualTo(PayerEvidence.NOTIFICATION_UNVERIFIED);
        assertThat(restored.evidenceMode()).isEqualTo(EvidenceMode.OBSERVED_NOTIFICATION);
        assertThat(restored.checkoutId()).isEqualTo(checkout.checkoutId());
        // Another amount, synthetic integration and non-integration all refuse, and change nothing on disk.
        refusedOn(2, true, "Invalid experimental store");    // genuine evidence on a binding not observed at this amount
        refusedOn(null, true, "Invalid experimental store"); // genuine evidence on a binding outside observed mode
        refusedOn(null, false, "Invalid experimental store");
        assertThat(bytes()).isEqualTo(stored);
    }

    @Test void reverseGuardRefusesAnObservedCheckoutInAnyOtherModeWhateverItsStatus() throws Exception {
        created(UUID.randomUUID());
        refusedOn(null, true, "Observed-mode store requires observed mode");   // open, no evidence
        refusedOn(null, false, "Observed-mode store requires observed mode");
        clock.now = START.plusSeconds(600);
        service = reopen(PINNED, true);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.EXPIRED);
        refusedOn(null, true, "Observed-mode store requires observed mode");   // terminal, no evidence
        refusedOn(null, false, "Observed-mode store requires observed mode");
    }

    @Test void observedProcessRefusesEveryOtherKindOfStoreContent() throws Exception {
        Instant now = START;
        UUID reference = UUID.randomUUID();
        PaymentRequest synthetic = new PaymentRequest(UUID.randomUUID(), PINNED, now, now.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1, reference);
        PaymentRequest receiver = new PaymentRequest(UUID.randomUUID(), PINNED, now, now.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1, null);
        PaymentRequest observedHere = new PaymentRequest(UUID.randomUUID(), PINNED, now, now.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1,
                UUID.randomUUID(), EvidenceMode.OBSERVED_NOTIFICATION);
        PaymentRequest observedTwo = new PaymentRequest(UUID.randomUUID(), 2, now, now.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1,
                UUID.randomUUID(), EvidenceMode.OBSERVED_NOTIFICATION);
        Candidate simulated = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, now, 5, Source.SIMULATED);
        Observation simulatedSeen = new Observation(simulated, Disposition.UNMATCHED, null, now);
        Observation syntheticSeen = new Observation(synthetic(5, now), Disposition.UNMATCHED, null, now);
        Observation genuineSeen = new Observation(genuine(5, now), Disposition.UNMATCHED, null, now);
        String refused = "Observed mode requires an empty or observed-mode store";
        record Case(List<PaymentRequest> requests, List<Observation> observations) {}
        for (Case invalid : List.of(new Case(List.of(synthetic), List.of()), new Case(List.of(receiver), List.of()),
                new Case(List.of(observedTwo), List.of()), new Case(List.of(observedHere, receiver), List.of()),
                new Case(List.of(observedHere), List.of(simulatedSeen)), new Case(List.of(observedHere), List.of(syntheticSeen)),
                new Case(List.of(), List.of(simulatedSeen)), new Case(List.of(), List.of(syntheticSeen)))) {
            store.save(new Snapshot(invalid.requests(), invalid.observations()));
            byte[] before = bytes();
            refusedOn(PINNED, true, refused);
            assertThat(bytes()).isEqualTo(before);
        }
        // Genuine candidates without the observed checkout came from another mode (for example an old live store): refused.
        store.save(new Snapshot(List.of(), List.of(genuineSeen)));
        refusedOn(PINNED, true, "Observed mode requires an empty or observed-mode store");
        // Accepted: one observed checkout at the pinned amount with genuine candidates only.
        store.save(new Snapshot(List.of(observedHere), List.of(genuineSeen)));
        assertThat(reopen(PINNED, true).view().requests()).hasSize(1);
        // An observed marker without an integration binding is invalid in every mode.
        PaymentRequest unbound = new PaymentRequest(UUID.randomUUID(), PINNED, now, now.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1,
                null, EvidenceMode.OBSERVED_NOTIFICATION);
        store.save(new Snapshot(List.of(unbound), List.of()));
        refusedOn(PINNED, true, "Invalid experimental store");
        refusedOn(null, true, "Invalid experimental store");
    }

    @Test void existingSyntheticBindingGuardStillRefusesGenuineEvidenceOutsideObservedMode() throws Exception {
        // The eb83f6c rule, unchanged for non-observed requests: genuine evidence on a synthetic integration binding.
        Instant now = START;
        Candidate event = genuine(863, now.plusSeconds(5));
        PaymentRequest bound = new PaymentRequest(UUID.randomUUID(), 863, now, now.plusSeconds(600), Status.PAYMENT_DETECTED, event.eventId(), 2, false,
                new ReviewGrant(event.eventId(), now.plusSeconds(2400), false), 2, UUID.randomUUID());
        store.save(new Snapshot(List.of(bound), List.of(new Observation(event, Disposition.MATCHED, bound.id(), now.plusSeconds(5)))));
        refusedOn(null, true, "Invalid experimental store");
        refusedOn(863, true, "Invalid observed mode"); // above the cap
        refusedOn(100, true, "Invalid experimental store");
    }

    // ---- PUT order and single use ---------------------------------------------------------------------------------

    @Test void putOrderIsAmountPinnedThenSingleUseThenHeldWhileReplayKeepsItsSemantics() throws Exception {
        UUID reference = UUID.randomUUID();
        refusedUnchanged(() -> service.putIntegration(reference, 2), 409, "OBSERVED_AMOUNT_MISMATCH");
        assertThat(file).doesNotExist();
        IntegrationCheckout checkout = created(reference);
        assertThat(checkout.evidenceMode()).isEqualTo(EvidenceMode.OBSERVED_NOTIFICATION);
        assertThat(checkout.amountMinor()).isEqualTo(PINNED);
        // The amount is held, yet a new reference is refused as spent, never as SAME_AMOUNT_HELD.
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), PINNED), 409, "OBSERVED_CHECKOUT_SPENT");
        // Mismatch is checked before single use.
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), 2), 409, "OBSERVED_AMOUNT_MISMATCH");
        // Replay of the bound reference: the lookup still comes first.
        ObservationService.IntegrationPut replay = service.putIntegration(reference, PINNED);
        assertThat(replay.created()).isFalse();
        assertThat(replay.checkout()).isEqualTo(checkout);
        refusedUnchanged(() -> service.putIntegration(reference, 2), 409, "REFERENCE_CONFLICT");
        // Still single use after the checkout ends, and after a restart.
        clock.now = START.plusSeconds(600);
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.EXPIRED);
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), PINNED), 409, "OBSERVED_CHECKOUT_SPENT");
        service = reopen(PINNED, true);
        refusedUnchanged(() -> service.putIntegration(UUID.randomUUID(), PINNED), 409, "OBSERVED_CHECKOUT_SPENT");
        assertThat(service.putIntegration(reference, PINNED).created()).isFalse();
        assertThat(service.view().requests()).hasSize(1);
    }

    @Test void observedPutRefusalsWriteNothingEvenAfterTheWindowClosed() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(600); // lazy expiry is due, but no refusal may publish it
        byte[] before = bytes();
        fails(() -> service.putIntegration(UUID.randomUUID(), PINNED), 409, "OBSERVED_CHECKOUT_SPENT");
        fails(() -> service.putIntegration(UUID.randomUUID(), 2), 409, "OBSERVED_AMOUNT_MISMATCH");
        assertThat(bytes()).isEqualTo(before);
        assertThat(mapper.readTree(file.toFile()).get("requests").get(0).get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        // The bound reference still replays, with lazy expiry as before.
        assertThat(service.putIntegration(reference, PINNED).checkout().status()).isEqualTo(Status.EXPIRED);
    }

    @Test void syntheticIntegrationModeKeepsTheExistingOrderAndNoMarker() {
        service = reopen(null, true);
        IntegrationCheckout first = service.putIntegration(UUID.randomUUID(), 863).checkout();
        assertThat(first.evidenceMode()).isNull();
        assertThat(only().evidenceMode()).isNull();
        fails(() -> service.putIntegration(UUID.randomUUID(), 863), 409, "SAME_AMOUNT_HELD");
        assertThat(service.putIntegration(UUID.randomUUID(), 864).created()).isTrue();
    }

    @Test void receiverCreateAndBothSyntheticSourcesAreRefusedBeforeAnyStateChange() throws Exception {
        refusedUnchanged(() -> service.create(new CreateRequest(PINNED)), 409, "RECEIVER_CREATE_DISABLED");
        refusedUnchanged(() -> service.create(null), 409, "RECEIVER_CREATE_DISABLED");
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(20);
        refusedUnchanged(() -> service.create(new CreateRequest(5)), 409, "RECEIVER_CREATE_DISABLED");
        refusedUnchanged(() -> service.ingestNotification(synthetic(PINNED, clock.now)), 422, "SYNTHETIC_DISABLED");
        refusedUnchanged(() -> service.ingest(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, PINNED, Source.SIMULATED)), 422, "SYNTHETIC_DISABLED");
        refusedUnchanged(() -> service.ingest(null), 422, "SYNTHETIC_DISABLED");
        // Not even a lazy expiry is published by a refusal: after the window, the stored status is still the old one.
        clock.now = START.plusSeconds(700);
        byte[] before = bytes();
        fails(() -> service.ingestNotification(synthetic(PINNED, clock.now)), 422, "SYNTHETIC_DISABLED");
        fails(() -> service.ingest(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, PINNED, Source.SIMULATED)), 422, "SYNTHETIC_DISABLED");
        assertThat(bytes()).isEqualTo(before);
        assertThat(mapper.readTree(file.toFile()).get("requests").get(0).get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.EXPIRED);
        assertThat(service.view().observations()).isEmpty();
    }

    // ---- Admission gate -------------------------------------------------------------------------------------------

    @Test void rightAmountWhileHeldIsAdmittedAndMatchesWhileEverythingElseIsGenuineDisabledWithEqualBytes() throws Exception {
        clock.now = START;
        refusedUnchanged(() -> service.ingestNotification(genuine(PINNED, clock.now)), 422, "GENUINE_DISABLED"); // no checkout
        UUID reference = UUID.randomUUID();
        IntegrationCheckout checkout = created(reference);
        clock.now = START.plusSeconds(20);
        refusedUnchanged(() -> service.ingestNotification(genuine(2, clock.now)), 422, "GENUINE_DISABLED");       // other amount
        refusedUnchanged(() -> service.ingestNotification(genuine(100, clock.now)), 422, "GENUINE_DISABLED");
        Candidate event = genuine(PINNED, clock.now);
        Observation matched = service.ingestNotification(event);
        assertThat(matched.disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(matched.requestId()).isEqualTo(checkout.checkoutId());
        IntegrationCheckout detected = service.integrationCheckout(reference);
        assertThat(detected.status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(detected.evidence()).isEqualTo(PayerEvidence.NOTIFICATION_UNVERIFIED);
        assertThat(detected.reviewExpiresAt()).isEqualTo(START.plusSeconds(600 + 1800));
        confirm(checkout.checkoutId(), event.eventId());
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        // Terminal: refused, and a post-confirmation transfer is never stored.
        refusedUnchanged(() -> service.ingestNotification(genuine(PINNED, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void exactRetryIsAcknowledgedBeforeTheGateEvenAfterConfirmationOrExpiry() throws Exception {
        created(UUID.randomUUID());
        clock.now = START.plusSeconds(20);
        Candidate event = genuine(PINNED, clock.now);
        Observation stored = service.ingestNotification(event);
        confirm(only().id(), event.eventId());
        byte[] before = bytes();
        assertThat(service.ingestNotification(event)).isEqualTo(stored);           // lost acknowledgement after confirmation
        clock.now = START.plusSeconds(4000);
        assertThat(service.ingestNotification(event)).isEqualTo(stored);           // and long after every deadline
        assertThat(bytes()).isEqualTo(before);
        // A reused event ID with other content is not an exact retry: the gate refuses it with no state change.
        Candidate reused = new Candidate(event.eventId(), event.notificationId(), 2, event.observedAt(), PINNED, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
        refusedUnchanged(() -> service.ingestNotification(reused), 422, "GENUINE_DISABLED");
    }

    @Test void outsideTheHoldNothingIsStoredAndNoLazyExpiryIsPublished() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(600); // window closed, no grant
        byte[] before = bytes();
        fails(() -> service.ingestNotification(genuine(PINNED, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(bytes()).isEqualTo(before);
        assertThat(mapper.readTree(file.toFile()).get("requests").get(0).get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.EXPIRED);
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void reviewHoldAdmitsUntilTheReviewDeadlineThenRefuses() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(20);
        service.ingestNotification(genuine(PINNED, clock.now));
        clock.now = START.plusSeconds(700); // window closed, unconfirmed grant running: held
        byte[] held = bytes();
        fails(() -> service.ingestNotification(genuine(2, clock.now)), 422, "GENUINE_DISABLED"); // wrong amount, even while held
        assertThat(bytes()).isEqualTo(held);
        assertThat(service.view().observations()).hasSize(1);
        Observation late = service.ingestNotification(genuine(PINNED, clock.now));
        assertThat(late.disposition()).isEqualTo(Disposition.EXPIRED);
        IntegrationCheckout ambiguous = service.integrationCheckout(reference);
        assertThat(ambiguous.status()).isEqualTo(Status.AMBIGUOUS); // a second same-amount notification disqualifies
        assertThat(only().reviewGrant().revoked()).isTrue();
        clock.now = START.plusSeconds(2400); // review deadline reached: not held
        refusedUnchanged(() -> service.ingestNotification(genuine(PINNED, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.REVIEW_EXPIRED);
        assertThat(service.view().observations()).hasSize(2);
    }

    @Test void admissionDuringAnAmbiguousRevokedGrantHoldIsStoredAndCannotRestoreTheCheckout() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(20);
        service.ingestNotification(genuine(PINNED, clock.now));
        clock.now = START.plusSeconds(30);
        Observation second = service.ingestNotification(genuine(PINNED, clock.now)); // second genuine → AMBIGUOUS
        assertThat(second.disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(only().reviewGrant().revoked()).isTrue();
        clock.now = START.plusSeconds(900); // window closed; AMBIGUOUS with a revoked grant is still held
        Observation third = service.ingestNotification(genuine(PINNED, clock.now));
        assertThat(service.view().observations()).hasSize(3).contains(third);
        IntegrationCheckout after = service.integrationCheckout(reference);
        assertThat(after.status()).isEqualTo(Status.AMBIGUOUS);
        fails(() -> service.beginVerification(after.checkoutId(), new Confirmation(true, second.candidate().eventId())), 409, "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void lateUploadIsDelayedNeverMatchesAndStillCompetesWithALaterNotification() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(200);
        Observation delayed = service.ingestNotification(genuine(PINNED, START.plusSeconds(60))); // observed 140 s before admission
        assertThat(delayed.disposition()).isEqualTo(Disposition.DELAYED);
        assertThat(delayed.requestId()).isNull();
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.AWAITING_PAYMENT);
        // Existing rule: a same-amount observation admitted after creation competes, so the run fails towards AMBIGUOUS.
        assertThat(service.ingestNotification(genuine(PINNED, clock.now)).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.AMBIGUOUS);
    }

    @Test void invalidationOfTheMatchedNotificationDisqualifiesTheObservedCheckout() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(20);
        Candidate matched = genuine(PINNED, clock.now);
        assertThat(service.ingestNotification(matched).disposition()).isEqualTo(Disposition.MATCHED);
        clock.now = START.plusSeconds(40);
        service.invalidate(matched.eventId(), new Invalidation("NOTIFICATION_CHANGED"));
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(only().reviewGrant().revoked()).isTrue();
        fails(() -> service.beginVerification(only().id(), new Confirmation(true, matched.eventId())), 409, "VERIFICATION_NOT_ELIGIBLE");
    }

    @Test void resolveStillRefusesGenuineEvidenceOntoTheObservedCheckout() throws Exception {
        UUID reference = UUID.randomUUID();
        IntegrationCheckout checkout = created(reference);
        // An unmatched candidate admitted after creation competes, so the next one makes the checkout AMBIGUOUS with no
        // grant: every resolve condition then holds except the integration-binding guard.
        clock.now = START.plusSeconds(5);
        assertThat(service.ingestNotification(genuine(PINNED, START.minusSeconds(5))).disposition()).isEqualTo(Disposition.UNMATCHED);
        clock.now = START.plusSeconds(10);
        Observation ambiguous = service.ingestNotification(genuine(PINNED, clock.now));
        assertThat(ambiguous.disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(only().reviewGrant()).isNull();
        refusedUnchanged(() -> service.resolve(ambiguous.candidate().eventId(), new Resolution(checkout.checkoutId(), true)), 409, "RESOLUTION_NOT_ELIGIBLE");
    }

    Candidate kaching(int amount, Instant observedAt) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, Source.TNG_NOTIFICATION_CANDIDATE, DEVICE,
                "INCOMING_CANDIDATE", "tng.receipt.kaching.en.observed.v1", "RULE_MATCHED");
    }

    @Test void kachingEvidenceMatchesExactlyLikeTheFirstRuleAndRestartsInObservedMode() throws Exception {
        UUID reference = UUID.randomUUID();
        IntegrationCheckout checkout = created(reference);
        clock.now = START.plusSeconds(20);
        refusedUnchanged(() -> service.ingestNotification(kaching(2, clock.now)), 422, "GENUINE_DISABLED"); // other amount
        Candidate event = kaching(PINNED, clock.now);
        Observation matched = service.ingestNotification(event);
        assertThat(matched.disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(matched.requestId()).isEqualTo(checkout.checkoutId());
        IntegrationCheckout detected = service.integrationCheckout(reference);
        assertThat(detected.status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(detected.evidence()).isEqualTo(PayerEvidence.NOTIFICATION_UNVERIFIED);
        assertThat(detected.evidenceMode()).isEqualTo(EvidenceMode.OBSERVED_NOTIFICATION);
        assertThat(detected.reviewExpiresAt()).isEqualTo(START.plusSeconds(600 + 1800));
        service = reopen(PINNED, true);
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.PAYMENT_DETECTED);
        confirm(checkout.checkoutId(), event.eventId());
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void sameAmountEvidenceFromEitherRuleIsAmbiguous() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(20);
        service.ingestNotification(genuine(PINNED, clock.now));
        clock.now = START.plusSeconds(30);
        assertThat(service.ingestNotification(kaching(PINNED, clock.now)).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(only().reviewGrant().revoked()).isTrue();
    }

    @Test void lateKachingUploadIsDelayedAndNeverMatches() throws Exception {
        UUID reference = UUID.randomUUID();
        created(reference);
        clock.now = START.plusSeconds(200);
        Observation delayed = service.ingestNotification(kaching(PINNED, START.plusSeconds(60))); // observed 140 s before admission
        assertThat(delayed.disposition()).isEqualTo(Disposition.DELAYED);
        assertThat(delayed.requestId()).isNull();
        assertThat(service.integrationCheckout(reference).status()).isEqualTo(Status.AWAITING_PAYMENT);
    }
}
