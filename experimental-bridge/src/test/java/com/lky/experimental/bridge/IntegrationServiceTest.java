package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** Service-level integration contract: reference binding, holds, revision and the synthetic-only guards. */
class IntegrationServiceTest {
    static final UUID DEVICE = NotificationCandidateTest.DEVICE_ID;
    static final Instant START = LifecycleTest.START;
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    LifecycleTest.TestClock clock;
    Path file;

    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        clock = new LifecycleTest.TestClock(); file = directory.resolve("state.json");
        store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE, 1800, true);
    }
    @AfterEach void close() throws Exception { store.close(); }
    void restart(boolean syntheticOnly) {
        try { store.close(); } catch (Exception failure) { throw new IllegalStateException(failure); }
        store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE, 1800, syntheticOnly);
    }
    static UUID reference() { return UUID.randomUUID(); }
    IntegrationCheckout put(UUID reference, int amount, boolean created) {
        ObservationService.IntegrationPut result = service.putIntegration(reference, amount);
        assertThat(result.created()).isEqualTo(created);
        return result.checkout();
    }
    IntegrationCheckout get(UUID reference) { return service.integrationCheckout(reference); }
    Candidate candidate(int amount, Source source, Instant observedAt) {
        return source == Source.SIMULATED
                ? new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, source)
                : new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, source, DEVICE,
                    "INCOMING_CANDIDATE", source == Source.SYNTHETIC ? "synthetic.debug.v1" : "tng.receipt.en.observed.v1",
                    source == Source.SYNTHETIC ? "SYNTHETIC" : "RULE_MATCHED");
    }
    Observation ingest(Candidate event) { return event.source() == Source.SIMULATED ? service.ingest(event) : service.ingestNotification(event); }
    static void fails(Runnable action, int status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BridgeFailure.class, failure -> {
            assertThat(failure.code).isEqualTo(code); assertThat(failure.status).isEqualTo(status);
        });
    }
    PaymentRequest request(UUID id) { return service.view().requests().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(); }

    @Test void putCreatesOnceThenReplaysTheBoundCheckoutWhateverItsStatusAndConflictsOnAnotherAmount() {
        UUID reference = reference();
        IntegrationCheckout created = put(reference, 863, true);
        assertThat(created.reference()).isEqualTo(reference);
        assertThat(created.amountMinor()).isEqualTo(863);
        assertThat(created.status()).isEqualTo(Status.AWAITING_PAYMENT);
        assertThat(created.evidence()).isEqualTo(PayerEvidence.NONE);
        assertThat(created.revision()).isEqualTo(1);
        assertThat(created.createdAt()).isEqualTo(START);
        assertThat(created.expiresAt()).isEqualTo(START.plusSeconds(600));
        assertThat(created.reviewExpiresAt()).isNull();
        PaymentRequest stored = request(created.checkoutId());
        assertThat(stored.integrationReference()).isEqualTo(reference);
        assertThat(stored.lifecycleVersion()).isEqualTo(2);

        // The checkout holds 863 itself; the replay must not be blocked by its own hold.
        assertThat(put(reference, 863, false)).isEqualTo(created);
        fails(() -> service.putIntegration(reference, 864), 409, "REFERENCE_CONFLICT");
        assertThat(service.view().requests()).hasSize(1);

        clock.now = START.plusSeconds(600);
        IntegrationCheckout expired = put(reference, 863, false);
        assertThat(expired.checkoutId()).isEqualTo(created.checkoutId());
        assertThat(expired.status()).isEqualTo(Status.EXPIRED);
        fails(() -> service.putIntegration(reference, 864), 409, "REFERENCE_CONFLICT");
        assertThat(service.view().requests()).hasSize(1);
    }

    @Test void newReferenceForAHeldAmountCreatesNothingWhetherTheHolderIsAnIntegrationCheckoutOrNot() throws Exception {
        UUID first = reference();
        put(first, 863, true);
        byte[] before = Files.readAllBytes(file);
        fails(() -> service.putIntegration(reference(), 863), 409, "SAME_AMOUNT_HELD");
        fails(() -> service.create(new CreateRequest(863)), 409, "SAME_AMOUNT_HELD");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.view().requests()).hasSize(1);

        PaymentRequest receiverOwned = service.create(new CreateRequest(500));
        assertThat(receiverOwned.integrationReference()).isNull();
        assertThat(receiverOwned.revision()).isEqualTo(1);
        UUID blocked = reference();
        fails(() -> service.putIntegration(blocked, 500), 409, "SAME_AMOUNT_HELD");
        fails(() -> service.integrationCheckout(blocked), 404, "REFERENCE_NOT_FOUND");
        assertThat(put(reference(), 501, true).amountMinor()).isEqualTo(501);
        assertThat(service.view().requests()).hasSize(3);
    }

    @Test void unknownReferenceIsDistinctAndInvalidAmountsAreRejectedBeforeLookup() {
        fails(() -> service.integrationCheckout(reference()), 404, "REFERENCE_NOT_FOUND");
        UUID reference = reference(); put(reference, 863, true);
        for (Integer invalid : new Integer[]{null, 0, -1, ObservationService.MAX_AMOUNT + 1})
            fails(() -> service.putIntegration(reference, invalid), 422, "AMOUNT_INVALID");
        assertThat(service.view().requests()).hasSize(1);
    }

    @Test void replayStillSucceedsAtRequestCapacityWhileANewReferenceIsRefused() {
        UUID reference = reference(); IntegrationCheckout bound = put(reference, ObservationService.MAX_AMOUNT, true);
        List<PaymentRequest> requests = new ArrayList<>(service.view().requests());
        for (int i = 1; i < ObservationService.MAX_REQUESTS; i++)
            requests.add(new PaymentRequest(UUID.randomUUID(), i, START, START.plusSeconds(600), Status.AWAITING_PAYMENT, null, 2, false, null, 1, null));
        store.save(new Snapshot(requests, List.of())); restart(true);
        assertThat(put(reference, ObservationService.MAX_AMOUNT, false)).isEqualTo(bound);
        fails(() -> service.putIntegration(reference(), 999_999), 409, "REQUEST_CAPACITY");
    }

    @Test void storeFailureReturnsStoreUnavailableAndBindsNothing() throws Exception {
        UUID reference = reference();
        Files.createDirectory(file);
        fails(() -> service.putIntegration(reference, 863), 503, "STORE_UNAVAILABLE");
        fails(() -> service.integrationCheckout(reference), 404, "REFERENCE_NOT_FOUND");
        assertThat(service.view().requests()).isEmpty();
        Files.delete(file);
        assertThat(put(reference, 863, true).revision()).isEqualTo(1);
    }

    @Test void revisionIncrementsOnMatchVerifyConfirmAndTheInvisibleClosedLatchButNotOnUnchangedReads() {
        UUID reference = reference(); IntegrationCheckout created = put(reference, 863, true);
        Candidate event = candidate(863, Source.SYNTHETIC, START.plusSeconds(30)); clock.now = START.plusSeconds(30);
        assertThat(ingest(event).disposition()).isEqualTo(Disposition.MATCHED);
        IntegrationCheckout matched = get(reference);
        assertThat(matched.status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(matched.evidence()).isEqualTo(PayerEvidence.SYNTHETIC);
        assertThat(matched.reviewExpiresAt()).isEqualTo(START.plusSeconds(2400));
        assertThat(matched.revision()).isEqualTo(2);
        service.beginVerification(created.checkoutId(), new Confirmation(true, event.eventId()));
        assertThat(get(reference).revision()).isEqualTo(3);
        service.confirm(created.checkoutId(), new Confirmation(true, event.eventId()));
        IntegrationCheckout confirmed = get(reference);
        assertThat(confirmed.status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(confirmed.revision()).isEqualTo(4);
        // Idempotent repeats and reads change nothing, so revision does not move.
        service.confirm(created.checkoutId(), new Confirmation(true, event.eventId()));
        for (int i = 0; i < 3; i++) { service.view(); service.checkout(created.checkoutId()); assertThat(get(reference)).isEqualTo(confirmed); }
        // Lazy expiry during a read latches paymentClosed on the terminal request: a new revision, same allowlist content.
        clock.now = START.plusSeconds(600);
        IntegrationCheckout latched = get(reference);
        assertThat(latched.revision()).isEqualTo(5);
        assertThat(latched).usingRecursiveComparison().ignoringFields("revision").isEqualTo(confirmed);
        assertThat(request(created.checkoutId()).paymentClosed()).isTrue();
        assertThat(get(reference).revision()).isEqualTo(5);
    }

    @Test void revisionIncrementsOnLazyExpiryWithoutAGrant() {
        UUID reference = reference(); put(reference, 863, true);
        clock.now = START.plusSeconds(599); assertThat(get(reference).revision()).isEqualTo(1);
        clock.now = START.plusSeconds(600);
        IntegrationCheckout expired = get(reference);
        assertThat(expired.status()).isEqualTo(Status.EXPIRED);
        assertThat(expired.revision()).isEqualTo(2);
        clock.now = START.plusSeconds(6000); assertThat(get(reference).revision()).isEqualTo(2);
    }

    @Test void revisionIncrementsOnAmbiguityDisqualificationAndReviewExpiryButNotOnARepeatedNoOpDisqualify() {
        UUID reference = reference(); put(reference, 863, true);
        clock.now = START.plusSeconds(10);
        assertThat(ingest(candidate(863, Source.SYNTHETIC, clock.now)).disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(get(reference).revision()).isEqualTo(2);
        assertThat(ingest(candidate(863, Source.SYNTHETIC, clock.now)).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        IntegrationCheckout ambiguous = get(reference);
        assertThat(ambiguous.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(ambiguous.revision()).isEqualTo(3);
        // Already ambiguous with a revoked grant: a further competitor disqualifies nothing new.
        ingest(candidate(863, Source.SYNTHETIC, clock.now));
        assertThat(get(reference).revision()).isEqualTo(3);
        clock.now = START.plusSeconds(600);
        IntegrationCheckout closed = get(reference);
        assertThat(closed.status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(closed.revision()).isEqualTo(4);
        clock.now = START.plusSeconds(2400);
        IntegrationCheckout reviewExpired = get(reference);
        assertThat(reviewExpired.status()).isEqualTo(Status.REVIEW_EXPIRED);
        assertThat(reviewExpired.revision()).isEqualTo(5);
    }

    /** Unmatched early evidence makes a later in-window candidate compete, leaving a grant-free AMBIGUOUS checkout that resolve accepts. */
    UUID[] resolvableAmbiguity(int amount, Source source, boolean integration) {
        UUID id = integration ? put(reference(), amount, true).checkoutId() : service.create(new CreateRequest(amount)).id();
        clock.now = clock.now.plusSeconds(10);
        assertThat(ingest(candidate(amount, source, clock.now.minusSeconds(11))).disposition()).isEqualTo(Disposition.UNMATCHED);
        Candidate competing = candidate(amount, source, clock.now);
        assertThat(ingest(competing).disposition()).isEqualTo(Disposition.AMBIGUOUS);
        assertThat(request(id).status()).isEqualTo(Status.AMBIGUOUS);
        assertThat(request(id).reviewGrant()).isNull();
        return new UUID[]{id, competing.eventId()};
    }

    @Test void resolveRefusesGenuineEvidenceForAnIntegrationCheckoutButStillAllowsItForReceiverRequests() {
        restart(false); // Genuine admission allowed, isolating the resolve guard from GENUINE_DISABLED.
        UUID[] integration = resolvableAmbiguity(863, Source.TNG_NOTIFICATION_CANDIDATE, true);
        PaymentRequest before = request(integration[0]);
        fails(() -> service.resolve(integration[1], new Resolution(integration[0], true)), 409, "RESOLUTION_NOT_ELIGIBLE");
        assertThat(request(integration[0])).isEqualTo(before);
        UUID[] receiver = resolvableAmbiguity(500, Source.TNG_NOTIFICATION_CANDIDATE, false);
        assertThat(service.resolve(receiver[1], new Resolution(receiver[0], true)).disposition()).isEqualTo(Disposition.MATCHED);
        assertThat(request(receiver[0]).status()).isEqualTo(Status.PAYMENT_DETECTED);
    }

    @Test void resolveOfLabelledSyntheticEvidenceOntoAnIntegrationCheckoutIncrementsRevision() {
        UUID[] integration = resolvableAmbiguity(863, Source.SYNTHETIC, true);
        long before = request(integration[0]).revision();
        assertThat(before).isEqualTo(2);
        service.resolve(integration[1], new Resolution(integration[0], true));
        PaymentRequest resolved = request(integration[0]);
        assertThat(resolved.status()).isEqualTo(Status.PAYMENT_DETECTED);
        assertThat(resolved.revision()).isEqualTo(3);
        assertThat(service.integrationCheckout(resolved.integrationReference()).evidence()).isEqualTo(PayerEvidence.SYNTHETIC);
    }

    @Test void genuineCandidateIsRejectedBeforeExpiryOrAnyStateChangeAndCannotMatchCompeteOrDisqualify() throws Exception {
        UUID reference = reference(); IntegrationCheckout created = put(reference, 863, true);
        clock.now = START.plusSeconds(10);
        byte[] before = Files.readAllBytes(file);
        fails(() -> ingest(candidate(863, Source.TNG_NOTIFICATION_CANDIDATE, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.view().observations()).isEmpty();
        // Validation and device scope still run first.
        Candidate wrongDevice = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 863, Source.TNG_NOTIFICATION_CANDIDATE,
                UUID.randomUUID(), "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
        fails(() -> ingest(wrongDevice), 422, "DEVICE_SCOPE_INVALID");
        Candidate badProfile = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 863, Source.TNG_NOTIFICATION_CANDIDATE,
                DEVICE, "INCOMING_CANDIDATE", "synthetic.debug.v1", "RULE_MATCHED");
        fails(() -> ingest(badProfile), 422, "PROFILE_UNSUPPORTED");

        // No genuine competitor exists, so a labelled synthetic candidate matches cleanly.
        Candidate synthetic = candidate(863, Source.SYNTHETIC, clock.now);
        assertThat(ingest(synthetic).disposition()).isEqualTo(Disposition.MATCHED);
        IntegrationCheckout matched = get(reference);
        assertThat(matched.status()).isEqualTo(Status.PAYMENT_DETECTED);
        // A genuine same-amount candidate cannot disqualify the granted checkout.
        fails(() -> ingest(candidate(863, Source.TNG_NOTIFICATION_CANDIDATE, clock.now)), 422, "GENUINE_DISABLED");
        assertThat(get(reference)).isEqualTo(matched);
        assertThat(request(created.checkoutId()).reviewGrant().revoked()).isFalse();
        assertThat(service.view().observations()).hasSize(1);
        // Placement before expire(): past the deadline, the rejection still leaves the stored snapshot untouched.
        clock.now = START.plusSeconds(600);
        before = Files.readAllBytes(file);
        fails(() -> ingest(candidate(863, Source.TNG_NOTIFICATION_CANDIDATE, START.plusSeconds(590))), 422, "GENUINE_DISABLED");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(mapper.readValue(file.toFile(), Snapshot.class).requests().getFirst().paymentClosed()).isFalse();
        service.view(); // The next read performs the lazy change the rejection did not.
        assertThat(Files.readAllBytes(file)).isNotEqualTo(before);
        // Browser SIMULATED candidates stay accepted in integration mode.
        assertThat(service.ingest(candidate(42, Source.SIMULATED, clock.now)).disposition()).isEqualTo(Disposition.UNMATCHED);
    }

    @Test void exactRetryOfAGenuineEventStoredBeforeIntegrationModeIsAcknowledgedUnchanged() throws Exception {
        restart(false);
        Candidate genuine = candidate(700, Source.TNG_NOTIFICATION_CANDIDATE, START);
        Observation stored = ingest(genuine);
        assertThat(stored.disposition()).isEqualTo(Disposition.UNMATCHED);
        restart(true);
        clock.now = START.plusSeconds(3600);
        byte[] before = Files.readAllBytes(file);
        assertThat(ingest(genuine)).isEqualTo(stored);
        Candidate altered = new Candidate(genuine.eventId(), genuine.notificationId(), genuine.sequence(), genuine.observedAt(), 701,
                genuine.source(), genuine.deviceId(), genuine.classification(), genuine.classifierProfile(), genuine.evidenceQuality());
        fails(() -> ingest(altered), 422, "GENUINE_DISABLED");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.view().observations()).containsExactly(stored);
    }

    @Test void referenceMapRevisionAndBindingSurviveRestart() throws Exception {
        UUID reference = reference(); IntegrationCheckout created = put(reference, 863, true);
        clock.now = START.plusSeconds(5); ingest(candidate(863, Source.SYNTHETIC, clock.now));
        IntegrationCheckout matched = get(reference);
        ObjectNode disk = (ObjectNode) mapper.readTree(file.toFile()).get("requests").get(0);
        assertThat(disk.get("integrationReference").asText()).isEqualTo(reference.toString());
        assertThat(disk.get("revision").asLong()).isEqualTo(2);
        restart(true);
        assertThat(get(reference)).isEqualTo(matched);
        assertThat(put(reference, 863, false)).isEqualTo(matched);
        assertThat(matched.checkoutId()).isEqualTo(created.checkoutId());
    }

    @Test void snapshotsWithoutRevisionOrReferenceLoadAsRevisionZeroAndCountFromThere() throws Exception {
        PaymentRequest legacy = service.create(new CreateRequest(1234));
        ObjectNode tree = (ObjectNode) mapper.readTree(file.toFile());
        ObjectNode request = (ObjectNode) tree.get("requests").get(0);
        request.remove("revision"); request.remove("integrationReference");
        store.close(); Files.write(file, mapper.writeValueAsBytes(tree));
        store = new SnapshotStore(file, mapper); service = new ObservationService(store, clock, DEVICE, 1800, true);
        PaymentRequest loaded = request(legacy.id());
        assertThat(loaded.revision()).isZero();
        assertThat(loaded.integrationReference()).isNull();
        clock.now = START.plusSeconds(600);
        assertThat(request(legacy.id()).revision()).isEqualTo(1);
        assertThat(request(legacy.id()).status()).isEqualTo(Status.EXPIRED);
    }

    @Test void aProcessAdmittingGenuineEvidenceRefusesToServeAnOpenIntegrationCheckout() throws Exception {
        UUID reference = reference(); put(reference, 863, true);
        clock.now = START.plusSeconds(10); ingest(candidate(863, Source.SYNTHETIC, clock.now)); // grant until START+2400
        for (Instant at : List.of(START.plusSeconds(10), START.plusSeconds(700), START.plusSeconds(2399))) {
            clock.now = at; store.close(); store = new SnapshotStore(file, mapper);
            assertThatThrownBy(() -> new ObservationService(store, clock, DEVICE, 1800, false))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Open integration checkouts require integration mode");
        }
        clock.now = START.plusSeconds(2400); restart(false); // review deadline passed: nothing can change it any more
        assertThat(get(reference).status()).isEqualTo(Status.REVIEW_EXPIRED);
        restart(false);
        assertThat(service.create(new CreateRequest(863)).integrationReference()).isNull();
    }

    @Test void integrationBindingCarryingGenuineEvidenceFailsClosedInEitherMode() throws Exception {
        UUID reference = reference(); put(reference, 863, true);
        clock.now = START.plusSeconds(10); ingest(candidate(863, Source.SYNTHETIC, clock.now));
        clock.now = START.plusSeconds(3000); get(reference); // terminal, so only the evidence rule applies
        ObjectNode tree = (ObjectNode) mapper.readTree(file.toFile());
        ObjectNode event = (ObjectNode) tree.get("observations").get(0).get("candidate");
        event.put("source", "TNG_NOTIFICATION_CANDIDATE").put("classifierProfile", "tng.receipt.en.observed.v1").put("evidenceQuality", "RULE_MATCHED");
        for (boolean syntheticOnly : new boolean[]{true, false}) {
            store.close(); Files.write(file, mapper.writeValueAsBytes(tree)); store = new SnapshotStore(file, mapper);
            assertThatThrownBy(() -> new ObservationService(store, clock, DEVICE, 1800, syntheticOnly))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Invalid experimental store");
        }
        ((ObjectNode) tree.get("requests").get(0)).putNull("integrationReference"); // the same history on a receiver request loads
        store.close(); Files.write(file, mapper.writeValueAsBytes(tree)); store = new SnapshotStore(file, mapper);
        assertThat(new ObservationService(store, clock, DEVICE, 1800, false)).isNotNull();
    }

    @Test void invalidStoredIntegrationBindingsFailClosedAtStartup() throws Exception {
        UUID reference = reference(); put(reference, 863, true); put(reference(), 864, true);
        String valid = Files.readString(file);
        ObjectNode tree = (ObjectNode) mapper.readTree(valid);
        ArrayNode requests = (ArrayNode) tree.get("requests");
        List<java.util.function.Consumer<ObjectNode>> corruptions = List.of(
                second -> second.put("integrationReference", reference.toString()),
                second -> second.put("revision", -1),
                second -> second.put("revision", 0),
                second -> second.putNull("lifecycleVersion"));
        for (var corruption : corruptions) {
            ObjectNode copy = tree.deepCopy(); corruption.accept((ObjectNode) copy.get("requests").get(1));
            store.close(); Files.write(file, mapper.writeValueAsBytes(copy)); store = new SnapshotStore(file, mapper);
            assertThatThrownBy(() -> new ObservationService(store, clock, DEVICE, 1800, true))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Invalid experimental store");
        }
        assertThat(requests).hasSize(2);
        store.close(); Files.writeString(file, valid); store = new SnapshotStore(file, mapper);
        service = new ObservationService(store, clock, DEVICE, 1800, true);
        assertThat(get(reference).amountMinor()).isEqualTo(863);
        // A receiver request at revision 0 (pre-revision snapshot) remains valid.
        ObjectNode legacy = tree.deepCopy(); ((ObjectNode) legacy.get("requests").get(1)).put("revision", 0).putNull("integrationReference");
        store.close(); Files.write(file, mapper.writeValueAsBytes(legacy)); store = new SnapshotStore(file, mapper);
        assertThat(new ObservationService(store, clock, DEVICE, 1800, true)).isNotNull();
    }
}
