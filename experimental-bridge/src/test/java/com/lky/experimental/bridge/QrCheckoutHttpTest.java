package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QrCheckoutHttpTest {
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    BridgeAccess access;
    CheckoutHttpTest.MutableClock clock;
    MockMvc mvc;
    byte[] png;

    @BeforeEach void setup() throws Exception {
        mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        clock = new CheckoutHttpTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new SnapshotStore(directory.resolve("state.json"), mapper);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID);
        access = new BridgeAccess(NotificationHttpTest.environment());
        Path qrDirectory = Files.createDirectory(directory.resolve("private-qr"));
        png = ReceivingQrTest.nonpayablePng(256, 256); Files.write(qrDirectory.resolve("receiving.png"), png);
        configure(new ReceivingQr(qrDirectory.toString()));
    }
    void configure(ReceivingQr qr) {
        mvc = MockMvcBuilders.standaloneSetup(new BridgeController(service), new CheckoutController(service, access, qr))
                .setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper), new ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(access)).build();
    }
    @AfterEach void close() throws Exception { store.close(); }
    PaymentRequest create(int amount) { return service.create(new CreateRequest(amount)); }
    String payer(PaymentRequest request) { return access.payers.issue(request.id()); }
    String path(PaymentRequest request) { return "/api/checkout/" + request.id() + "/qr"; }
    void step(PaymentRequest request, String action, String body, int expected) throws Exception {
        mvc.perform(post("/api/requests/" + request.id() + "/" + action)
                .header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content(body.contains("historyChecked") ? body.substring(0, body.length() - 1) + ",\"expectedEventId\":\"" + service.view().requests().stream().filter(r -> r.id().equals(request.id())).findFirst().orElseThrow().observationId() + "\"}" : body)).andExpect(status().is(expected));
    }
    Candidate notification(int amount, Source source) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.instant(), amount, source,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE",
                source == Source.SYNTHETIC ? "synthetic.debug.v1" : "tng.receipt.en.observed.v1",
                source == Source.SYNTHETIC ? "SYNTHETIC" : "RULE_MATCHED");
    }

    @Test void authorizedPayerRetrievesUnalteredPngWithPrivateHeadersAndNoMetadataInCheckout() throws Exception {
        PaymentRequest request = create(1234); String token = payer(request);
        mvc.perform(get(path(request)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        String json = mvc.perform(get("/api/checkout/" + request.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(request.id().toString()))
                .andExpect(jsonPath("$.amountMinor").value(1234)).andReturn().getResponse().getContentAsString();
        Set<String> fields = new HashSet<>(); mapper.readTree(json).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(Set.of("id", "amountMinor", "createdAt", "expiresAt", "status", "evidence", "paymentWindowClosed", "reviewExpiresAt", "reviewAvailable"));
        assertThat(json).doesNotContain("receiving.png", "private-qr", directory.toString(), token,
                BridgeHttpTest.RECEIVER, NotificationHttpTest.LIVE, "base64", "classifier", "deviceId");
        assertThat(Files.readString(directory.resolve("state.json"))).doesNotContain("receiving.png", "private-qr", token);
    }

    @Test void missingWrongCrossRoleCrossRequestAndDuplicateCredentialsCannotRetrieveQr() throws Exception {
        PaymentRequest one = create(1234), two = create(5678); String token = payer(one);
        mvc.perform(get(path(one))).andExpect(status().isUnauthorized());
        for (String wrong : new String[] {"incorrect", BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE,
                NotificationHttpTest.LIVE, payer(two)})
            mvc.perform(get(path(one)).header("Authorization", "Bearer " + wrong)).andExpect(status().isUnauthorized());
        mvc.perform(get(path(two)).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get(path(one)).header("Authorization", "Bearer " + token, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/checkout/" + UUID.randomUUID() + "/qr").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test void queryEncodedMatrixPathAndWriteMethodsCannotBypassQrAuthorization() throws Exception {
        PaymentRequest request = create(1234); String token = payer(request); String canonical = path(request);
        mvc.perform(get(canonical).queryParam("token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get(canonical).queryParam("file", "receiving.png").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        for (String invalid : new String[] {canonical + "/", canonical + ";file=receiving.png",
                canonical.replace("/qr", "/%71r"), canonical.replace("/qr", "/../qr"),
                canonical.replace("/qr", "//qr"), canonical + "/receiving.png"})
            mvc.perform(get(canonical).with(r -> { r.setRequestURI(invalid); return r; })
                    .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        for (String method : new String[] {"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), canonical)
                    .header("Authorization", "Bearer " + token)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get(canonical).header("Authorization", "Bearer " + token)
                .with(r -> { r.setRemoteAddr("192.0.2.1"); return r; })).andExpect(status().isForbidden());
    }

    @Test void missingConfigurationIsNonpayableAndStillRequiresScopedAuthorization() throws Exception {
        configure(new ReceivingQr("")); PaymentRequest request = create(1234); String token = payer(request);
        mvc.perform(get(path(request))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(request)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("QR_NOT_CONFIGURED"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertThat(service.checkout(request.id()).status()).isEqualTo(Status.AWAITING_PAYMENT);
    }

    @Test void expiredAndConfirmedCheckoutsCannotRetrievePayableQr() throws Exception {
        PaymentRequest confirmed = create(1234), expired = create(5678);
        String confirmedToken = payer(confirmed), expiredToken = payer(expired);
        service.ingestNotification(notification(1234, Source.SYNTHETIC));
        step(confirmed, "verify", "{\"historyChecked\":true}", 200);
        step(confirmed, "confirm", "{\"historyChecked\":true}", 200);
        clock.now = clock.now.plusSeconds(600);
        for (String[] checkout : new String[][] {{path(confirmed), confirmedToken}, {path(expired), expiredToken}})
            mvc.perform(get(checkout[0]).header("Authorization", "Bearer " + checkout[1]))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CHECKOUT_NOT_PAYABLE"));
        assertThat(service.checkout(expired.id()).status()).isEqualTo(Status.EXPIRED);
        assertThat(service.checkout(confirmed.id()).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void detectedVerifyingAmbiguousAndReviewExpiredAllDenyQrOnServer() throws Exception {
        PaymentRequest request = create(1234); String token = payer(request);
        Candidate event = notification(1234, Source.SYNTHETIC); service.ingestNotification(event);
        mvc.perform(get(path(request)).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        step(request, "verify", "{\"historyChecked\":true}", 200);
        clock.now = request.expiresAt();
        mvc.perform(get(path(request)).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        service.ingestNotification(notification(1234, Source.SYNTHETIC));
        assertThat(service.checkout(request.id()).status()).isEqualTo(Status.AMBIGUOUS);
        mvc.perform(get(path(request)).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
        clock.now = request.expiresAt().plusSeconds(1800);
        assertThat(service.checkout(request.id()).status()).isEqualTo(Status.REVIEW_EXPIRED);
        mvc.perform(get(path(request)).header("Authorization", "Bearer " + token)).andExpect(status().isConflict());
    }

    @Test void manufacturedGenuineProfileEvidenceNeverConfirmsWithoutTwoExplicitAttestedReceiverSteps() throws Exception {
        // Constructed test envelope only: no genuine notification, wallet, or transfer is involved.
        PaymentRequest request = create(1234); String token = payer(request);
        Candidate candidate = notification(1234, Source.TNG_NOTIFICATION_CANDIDATE);
        mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + NotificationHttpTest.LIVE)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(candidate)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("MATCHED"));
        mvc.perform(get("/api/checkout/" + request.id()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_DETECTED"))
                .andExpect(jsonPath("$.evidence").value("NOTIFICATION_UNVERIFIED"));
        assertThat(service.view().observations().getFirst().candidate()).isEqualTo(candidate);
        step(request, "confirm", "{\"historyChecked\":true}", 409);
        for (String body : new String[] {"{}", "{\"historyChecked\":null}", "{\"historyChecked\":false}"})
            step(request, "verify", body, 422);
        assertThat(service.checkout(request.id()).status()).isEqualTo(Status.PAYMENT_DETECTED);
        step(request, "verify", "{\"historyChecked\":true}", 200);
        step(request, "verify", "{\"historyChecked\":true}", 200);
        step(request, "confirm", "{\"historyChecked\":false}", 422);
        assertThat(service.checkout(request.id()).status()).isEqualTo(Status.AWAITING_VERIFICATION);
        step(request, "confirm", "{\"historyChecked\":true}", 200);
        byte[] confirmedSnapshot = Files.readAllBytes(directory.resolve("state.json"));
        step(request, "confirm", "{\"historyChecked\":true}", 200);
        assertThat(Files.readAllBytes(directory.resolve("state.json"))).isEqualTo(confirmedSnapshot);
        assertThat(service.checkout(request.id()).status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        assertThat(service.view().observations()).hasSize(1);
    }
}
