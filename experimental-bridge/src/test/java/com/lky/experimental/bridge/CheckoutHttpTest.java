package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckoutHttpTest {
    @TempDir Path directory;
    Path file;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    MockMvc mvc;
    BridgeAccess access;
    MutableClock clock;

    @BeforeEach void setup() {
        var builder = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new BridgeConfiguration().strictJson().customize(builder);
        mapper = builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        file = directory.resolve("state.json");
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new SnapshotStore(file, mapper);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID);
        configureMvc();
    }

    void configureMvc() {
        access = new BridgeAccess(NotificationHttpTest.environment());
        mvc = MockMvcBuilders.standaloneSetup(new BridgeController(service), new CheckoutController(service, access))
                .setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper), new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(access)).build();
    }
    @AfterEach void close() throws Exception { store.close(); }

    PaymentRequest create(int amount) throws Exception {
        String json = mvc.perform(post("/api/requests").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":" + amount + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(json, PaymentRequest.class);
    }
    PayerLink link(UUID id) throws Exception {
        String json = mvc.perform(post("/api/requests/" + id + "/payer-access")
                .header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readValue(json, PayerLink.class);
    }
    String read(UUID id, String token, Status status, PayerEvidence evidence) throws Exception {
        return mvc.perform(get("/api/checkout/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(status.name()))
                .andExpect(jsonPath("$.evidence").value(evidence.name())).andReturn().getResponse().getContentAsString();
    }
    Candidate candidate(int amount) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.instant(), amount, Source.SYNTHETIC,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
    }
    void ingest(Candidate event, String disposition) throws Exception {
        mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + NotificationHttpTest.LIVE)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(event)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value(disposition));
    }
    void step(UUID id, String action, boolean checked, int status) throws Exception {
        mvc.perform(post("/api/requests/" + id + "/" + action).header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":" + checked + "}"))
                .andExpect(status().is(status));
    }

    @Test void creationAndScopedReadExposeOnlyTheIntendedRequestAllowlist() throws Exception {
        PaymentRequest request = create(1234); PaymentRequest unrelated = create(5678);
        PayerLink link = link(request.id());
        assertThat(link.requestId()).isEqualTo(request.id());
        assertThat(link.token()).matches("[A-Za-z0-9_-]{43}");
        String json = read(request.id(), link.token(), Status.AWAITING_PAYMENT, PayerEvidence.NONE);
        var tree = mapper.readTree(json); Set<String> fields = new HashSet<>(); tree.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(Set.of("id", "amountMinor", "createdAt", "expiresAt", "status", "evidence"));
        assertThat(tree.path("id").asText()).isEqualTo(request.id().toString());
        assertThat(tree.path("amountMinor").asInt()).isEqualTo(1234);
        assertThat(tree.path("createdAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(tree.path("expiresAt").asText()).isEqualTo(clock.instant().plusSeconds(600).toString());
        assertThat(json).doesNotContain(unrelated.id().toString(), "observationId", "eventId", "deviceId", "notificationId",
                "classification", "classifierProfile", "evidenceQuality", link.token(), BridgeHttpTest.RECEIVER,
                BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE);
    }

    @Test void missingWrongCrossRequestCrossRoleAndDuplicateCredentialsCannotRead() throws Exception {
        PaymentRequest one = create(1234), two = create(5678); PayerLink link = link(one.id());
        mvc.perform(get("/api/checkout/" + one.id())).andExpect(status().isUnauthorized());
        for (String token : new String[]{"incorrect", BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE, link(two.id()).token()})
            mvc.perform(get("/api/checkout/" + one.id()).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/checkout/" + two.id()).header("Authorization", "Bearer " + link.token())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/checkout/" + one.id()).header("Authorization", "Bearer " + link.token(), "Bearer " + link.token())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/checkout/" + UUID.randomUUID()).header("Authorization", "Bearer " + link.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/requests/" + UUID.randomUUID() + "/payer-access").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    }

    @Test void payerCapabilityCannotEscalateIntoAnyReceiverSimulatorOrDeviceRoute() throws Exception {
        PaymentRequest request = create(1234); PayerLink link = link(request.id());
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + link.token())).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/requests", "/api/observations", "/api/notification-candidates",
                "/api/notification-candidates/" + UUID.randomUUID() + "/invalidate", "/api/observations/" + UUID.randomUUID() + "/resolve",
                "/api/requests/" + request.id() + "/verify", "/api/requests/" + request.id() + "/confirm",
                "/api/requests/" + request.id() + "/payer-access"})
            mvc.perform(post(path).header("Authorization", "Bearer " + link.token()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        assertThat(service.view().requests()).hasSize(1); assertThat(service.view().observations()).isEmpty();
    }

    @Test void rawCanonicalPathsMethodsAndQueryCredentialsCannotBypassScope() throws Exception {
        PaymentRequest request = create(1234); PayerLink link = link(request.id());
        String path = "/api/checkout/" + request.id();
        for (String bad : new String[]{path + ";role=receiver", path + "/", "/api/checkout//" + request.id(),
                "/api/checkout/%" + request.id().toString().substring(0, 2) + request.id().toString().substring(2)})
            mvc.perform(get(path).with(r -> { r.setRequestURI(bad); return r; }).header("Authorization", "Bearer " + link.token()))
                    .andExpect(status().isNotFound());
        for (String method : new String[]{"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + link.token()))
                    .andExpect(status().isMethodNotAllowed());
        mvc.perform(get(path).queryParam("token", link.token())).andExpect(status().isUnauthorized());
        mvc.perform(get(path).queryParam("role", "receiver").header("Authorization", "Bearer " + link.token()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer " + link.token()).with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
                .andExpect(status().isForbidden());
    }

    @Test void syntheticEvidenceRemainsTentativeUntilBothReceiverStepsAndReplayIsUnchanged() throws Exception {
        PaymentRequest request = create(1234); PayerLink link = link(request.id()); Candidate event = candidate(1234);
        ingest(event, "MATCHED"); ingest(event, "MATCHED");
        String json = read(request.id(), link.token(), Status.PAYMENT_DETECTED, PayerEvidence.SYNTHETIC);
        assertThat(json).doesNotContain(event.eventId().toString(), event.notificationId().toString(), event.deviceId().toString(), "synthetic.debug.v1");
        Candidate changed = new Candidate(event.eventId(), event.notificationId(), 1, event.observedAt(), 1235, event.source(),
                event.deviceId(), event.classification(), event.classifierProfile(), event.evidenceQuality());
        mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + NotificationHttpTest.LIVE)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(changed))).andExpect(status().isConflict());
        step(request.id(), "confirm", true, 409); step(request.id(), "verify", false, 422);
        read(request.id(), link.token(), Status.PAYMENT_DETECTED, PayerEvidence.SYNTHETIC);
        step(request.id(), "verify", true, 200);
        read(request.id(), link.token(), Status.AWAITING_VERIFICATION, PayerEvidence.SYNTHETIC);
        step(request.id(), "confirm", false, 422);
        read(request.id(), link.token(), Status.AWAITING_VERIFICATION, PayerEvidence.SYNTHETIC);
        step(request.id(), "confirm", true, 200);
        read(request.id(), link.token(), Status.MANUALLY_CONFIRMED, PayerEvidence.SYNTHETIC);
        assertThat(service.view().observations()).hasSize(1);
        clock.now = clock.now.plusSeconds(601);
        read(request.id(), link.token(), Status.MANUALLY_CONFIRMED, PayerEvidence.SYNTHETIC);
    }

    @Test void unmatchedEvidenceDoesNotLeakAndCompetingCandidatesRequireExplicitReceiverResolution() throws Exception {
        PaymentRequest one = create(1234); PayerLink first = link(one.id());
        Candidate unmatched = candidate(7777); ingest(unmatched, "UNMATCHED");
        read(one.id(), first.token(), Status.AWAITING_PAYMENT, PayerEvidence.NONE);
        Candidate event = candidate(1234); ingest(event, "MATCHED"); step(one.id(), "verify", true, 200);
        Candidate competing = candidate(1234); ingest(competing, "AMBIGUOUS");
        read(one.id(), first.token(), Status.AMBIGUOUS, PayerEvidence.NONE);
        step(one.id(), "confirm", true, 409);
        String path = "/api/observations/" + competing.eventId() + "/resolve";
        for (boolean checked : new boolean[]{false, true}) mvc.perform(post(path).header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new Resolution(one.id(), checked))))
                .andExpect(status().is(checked ? 200 : 422));
        read(one.id(), first.token(), Status.PAYMENT_DETECTED, PayerEvidence.SYNTHETIC);
        step(one.id(), "confirm", true, 409); step(one.id(), "verify", true, 200); step(one.id(), "confirm", true, 200);
        read(one.id(), first.token(), Status.MANUALLY_CONFIRMED, PayerEvidence.SYNTHETIC);
    }

    @Test void identicalRequestAmountsStayAmbiguousWithoutAutomaticSelection() throws Exception {
        PaymentRequest one = create(1234), two = create(1234); PayerLink first = link(one.id()), second = link(two.id());
        ingest(candidate(1234), "AMBIGUOUS");
        for (PayerLink link : new PayerLink[]{first, second}) {
            read(link.requestId(), link.token(), Status.AMBIGUOUS, PayerEvidence.NONE);
            step(link.requestId(), "verify", true, 409); step(link.requestId(), "confirm", true, 409);
        }
    }

    @Test void payerPollingExpiresAwaitingDetectedAndVerificationStatesAndBlocksLateConfirmation() throws Exception {
        PaymentRequest awaiting = create(1001), detected = create(1002), verifying = create(1003);
        PayerLink a = link(awaiting.id()), d = link(detected.id()), v = link(verifying.id());
        ingest(candidate(1002), "MATCHED"); ingest(candidate(1003), "MATCHED"); step(verifying.id(), "verify", true, 200);
        clock.now = clock.now.plusSeconds(600);
        for (PayerLink link : new PayerLink[]{a, d, v}) {
            read(link.requestId(), link.token(), Status.EXPIRED, PayerEvidence.NONE);
            step(link.requestId(), "confirm", true, 409);
        }
        ingest(candidate(1001), "EXPIRED");
        assertThat(mapper.readValue(file.toFile(), Snapshot.class).requests()).allMatch(r -> r.status() == Status.EXPIRED);
    }

    @Test void sessionRotationRevokesLinksWithoutChangingSnapshotsOrLosingReplayHistory() throws Exception {
        PaymentRequest request = create(1234); PayerLink old = link(request.id()); Candidate event = candidate(1234); ingest(event, "MATCHED");
        byte[] previous = Files.readAllBytes(file);
        store.close(); store = new SnapshotStore(file, mapper);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID); configureMvc();
        mvc.perform(get("/api/checkout/" + request.id()).header("Authorization", "Bearer " + old.token())).andExpect(status().isUnauthorized());
        PayerLink replacement = link(request.id()); assertThat(replacement.token()).isNotEqualTo(old.token());
        read(request.id(), replacement.token(), Status.PAYMENT_DETECTED, PayerEvidence.SYNTHETIC);
        ingest(event, "MATCHED"); assertThat(service.view().observations()).hasSize(1);
        assertThat(Files.readAllBytes(file)).isEqualTo(previous);
        assertThat(Files.readString(file)).doesNotContain(old.token(), replacement.token(), BridgeHttpTest.RECEIVER, NotificationHttpTest.LIVE);
    }

    @Test void checkoutAmountValidationRetainsExactBoundedIntegerMinorUnits() throws Exception {
        for (String invalid : new String[]{"0", "-1", "1000001", "null"})
            mvc.perform(post("/api/requests").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":" + invalid + "}"))
                    .andExpect(status().isUnprocessableEntity());
        for (String invalid : new String[]{"1.1", "\"1234\"", "2147483648"})
            mvc.perform(post("/api/requests").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":" + invalid + "}"))
                    .andExpect(status().isBadRequest());
        for (int value : new int[]{1, 1_000_000}) {
            PaymentRequest request = create(value); PayerLink link = link(request.id());
            assertThat(mapper.readTree(read(request.id(), link.token(), Status.AWAITING_PAYMENT, PayerEvidence.NONE)).path("amountMinor").asInt()).isEqualTo(value);
        }
    }

    static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
