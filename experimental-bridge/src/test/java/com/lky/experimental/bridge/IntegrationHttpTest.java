package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Filter, role mapping, wire framing and payer copy for the integration routes. */
class IntegrationHttpTest {
    static final String INTEGRATION = "I".repeat(40);
    static final String ROUTE = "/api/integrations/checkouts/";
    static final Set<String> ALLOWLIST = Set.of("reference", "checkoutId", "amountMinor", "status", "evidence", "createdAt",
            "expiresAt", "reviewExpiresAt", "revision");
    @TempDir Path directory;
    Path file;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    MockMvc mvc;
    BridgeAccess access;
    CheckoutHttpTest.MutableClock clock;

    static MockEnvironment environment() { return NotificationHttpTest.environment().withProperty("bridge.integration-token", INTEGRATION); }

    @BeforeEach void setup() {
        var builder = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new BridgeConfiguration().strictJson().customize(builder);
        mapper = builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        file = directory.resolve("state.json");
        clock = new CheckoutHttpTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new SnapshotStore(file, mapper);
        configure(environment());
    }
    void configure(MockEnvironment environment) {
        access = new BridgeAccess(environment);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID, 1800, access.integrationEnabled());
        mvc = MockMvcBuilders.standaloneSetup(new BridgeController(service), new CheckoutController(service, access, new ReceivingQr("")),
                        new IntegrationController(service))
                .setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper), new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(access)).build();
    }
    @AfterEach void close() throws Exception { store.close(); }

    MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse();
    }
    MockHttpServletResponse put(String reference, String body, String token) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(ROUTE + reference)
                .contentType(MediaType.APPLICATION_JSON).content(body), token);
    }
    MockHttpServletResponse read(String reference, String token) throws Exception { return send(get(ROUTE + reference), token); }
    static String body(int amount) { return "{\"amountMinor\":" + amount + ",\"currency\":\"MYR\"}"; }
    static void framed(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"" + code + "\"}");
    }
    Set<String> fields(String json) throws Exception {
        Set<String> names = new HashSet<>(); mapper.readTree(json).fieldNames().forEachRemaining(names::add); return names;
    }

    @Test void integrationCredentialIsDeniedEveryOtherRouteIncludingHealthStateReviewResolveAndPayerMaterial() throws Exception {
        String reference = UUID.randomUUID().toString();
        IntegrationCheckout bound = mapper.readValue(put(reference, body(863), INTEGRATION).getContentAsString(), IntegrationCheckout.class);
        UUID id = bound.checkoutId(), event = UUID.randomUUID();
        String confirmation = "{\"historyChecked\":true,\"expectedEventId\":\"" + event + "\"}";
        List<MockHttpServletRequestBuilder> routes = List.of(
                get("/api/health"), get("/api/state"),
                post("/api/requests").contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":1234}"),
                post("/api/observations").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/notification-candidates").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/notification-candidates/" + event + "/invalidate").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOTIFICATION_CHANGED\"}"),
                post("/api/observations/" + event + "/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + id + "\",\"historyChecked\":true}"),
                post("/api/requests/" + id + "/verify").contentType(MediaType.APPLICATION_JSON).content(confirmation),
                post("/api/requests/" + id + "/confirm").contentType(MediaType.APPLICATION_JSON).content(confirmation),
                post("/api/requests/" + id + "/payer-access").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/requests/" + id + "/payer-revoke").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/api/checkout/" + id), get("/api/checkout/" + id + "/qr"));
        byte[] before = Files.readAllBytes(file);
        for (MockHttpServletRequestBuilder route : routes) framed(send(route, INTEGRATION), 401, "UNAUTHORIZED");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.view().requests()).hasSize(1);
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void receiverSimulationLivePayerAndMissingCredentialsAreDeniedTheIntegrationRoutes() throws Exception {
        String reference = UUID.randomUUID().toString();
        assertThat(put(reference, body(863), INTEGRATION).getStatus()).isEqualTo(201);
        UUID id = service.view().requests().getFirst().id();
        String payer = mapper.readValue(send(post("/api/requests/" + id + "/payer-access").contentType(MediaType.APPLICATION_JSON).content("{}"),
                BridgeHttpTest.RECEIVER).getContentAsString(), PayerLink.class).token();
        byte[] before = Files.readAllBytes(file);
        for (String token : new String[]{null, "", BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE, payer, INTEGRATION + "x"}) {
            framed(read(reference, token), 401, "UNAUTHORIZED");
            framed(put(reference, body(863), token), 401, "UNAUTHORIZED");
            framed(put(UUID.randomUUID().toString(), body(864), token), 401, "UNAUTHORIZED");
        }
        // Exactly one Authorization header is accepted.
        framed(send(get(ROUTE + reference).header("Authorization", "Bearer " + INTEGRATION, "Bearer " + INTEGRATION), null), 401, "UNAUTHORIZED");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(service.view().requests()).hasSize(1);
        // The existing credentials keep their own routes.
        assertThat(send(get("/api/health"), BridgeHttpTest.RECEIVER).getStatus()).isEqualTo(200);
        assertThat(send(get("/api/checkout/" + id), payer).getStatus()).isEqualTo(200);
    }

    @Test void putCreatesReplaysConflictsAndReportsHeldAmountsWithExactAllowlistedBodies() throws Exception {
        String reference = UUID.randomUUID().toString();
        MockHttpServletResponse created = put(reference, body(863), INTEGRATION);
        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(fields(created.getContentAsString())).isEqualTo(ALLOWLIST);
        JsonNode first = mapper.readTree(created.getContentAsString());
        assertThat(first.get("reference").asText()).isEqualTo(reference);
        assertThat(first.get("amountMinor").asInt()).isEqualTo(863);
        assertThat(first.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(first.get("evidence").asText()).isEqualTo("NONE");
        assertThat(first.get("revision").asLong()).isEqualTo(1);
        assertThat(first.get("reviewExpiresAt").isNull()).isTrue();
        assertThat(first.get("createdAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(first.get("expiresAt").asText()).isEqualTo("2026-01-01T00:10:00Z");

        MockHttpServletResponse replay = put(reference, body(863), INTEGRATION);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getContentAsString()).isEqualTo(created.getContentAsString());
        MockHttpServletResponse readBack = read(reference, INTEGRATION);
        assertThat(readBack.getStatus()).isEqualTo(200);
        assertThat(readBack.getContentAsString()).isEqualTo(created.getContentAsString());
        framed(put(reference, body(864), INTEGRATION), 409, "REFERENCE_CONFLICT");
        String other = UUID.randomUUID().toString();
        framed(put(other, body(863), INTEGRATION), 409, "SAME_AMOUNT_HELD");
        framed(read(other, INTEGRATION), 404, "REFERENCE_NOT_FOUND");
        assertThat(service.view().requests()).hasSize(1);
    }

    @Test void allowlistedReadExposesSyntheticEvidenceWithoutCandidateGrantOrPayerMaterial() throws Exception {
        String reference = UUID.randomUUID().toString();
        put(reference, body(1234), INTEGRATION);
        clock.now = clock.now.plusSeconds(20);
        Candidate event = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 1234, Source.SYNTHETIC,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
        assertThat(send(post("/api/notification-candidates").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(event)),
                NotificationHttpTest.LIVE).getStatus()).isEqualTo(200);
        String detected = read(reference, INTEGRATION).getContentAsString();
        assertThat(fields(detected)).isEqualTo(ALLOWLIST);
        JsonNode view = mapper.readTree(detected);
        assertThat(view.get("status").asText()).isEqualTo("PAYMENT_DETECTED");
        assertThat(view.get("evidence").asText()).isEqualTo("SYNTHETIC");
        assertThat(view.get("revision").asLong()).isEqualTo(2);
        assertThat(view.get("reviewExpiresAt").asText()).isEqualTo("2026-01-01T00:40:00Z");
        assertThat(detected).doesNotContain(event.eventId().toString(), event.notificationId().toString(), event.deviceId().toString(),
                "observationId", "reviewGrant", "paymentClosed", "lifecycleVersion", "classifierProfile", "token");
    }

    @Test void referenceNotFoundIsFramedDistinctlyFromFilterNotFoundAndDisabledIntegration() throws Exception {
        String reference = UUID.randomUUID().toString();
        framed(read(reference, INTEGRATION), 404, "REFERENCE_NOT_FOUND");
        framed(put(reference.toUpperCase(), body(863), INTEGRATION), 404, "NOT_FOUND");
        framed(read(reference.toUpperCase(), INTEGRATION), 404, "NOT_FOUND");
        for (String path : new String[]{ROUTE + reference + "/", ROUTE + reference + "/extra", ROUTE + "not-a-uuid", ROUTE.substring(0, ROUTE.length() - 1),
                ROUTE + reference.replace("-", "%2D"), ROUTE + reference + ";x=1"})
            framed(send(get(path), INTEGRATION), 404, "NOT_FOUND");
        framed(send(get(ROUTE + reference + "?x=1"), INTEGRATION), 404, "NOT_FOUND");
        framed(send(get(ROUTE + reference + "?x=1"), BridgeHttpTest.RECEIVER), 401, "UNAUTHORIZED");
        for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.DELETE, HttpMethod.PATCH})
            framed(send(request(method, ROUTE + reference).contentType(MediaType.APPLICATION_JSON).content(body(863)), INTEGRATION), 405, "METHOD_INVALID");
        assertThat(service.view().requests()).isEmpty();

        configure(NotificationHttpTest.environment()); // No integration credential configured.
        framed(read(reference, INTEGRATION), 404, "INTEGRATION_DISABLED");
        framed(put(reference, body(863), BridgeHttpTest.RECEIVER), 404, "INTEGRATION_DISABLED");
        assertThat(service.view().requests()).isEmpty();
    }

    @Test void putBodyUsesTheStrictJsonContentTypeAndSizeRules() throws Exception {
        String reference = UUID.randomUUID().toString();
        framed(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(ROUTE + reference).content(body(863)), INTEGRATION), 415, "JSON_REQUIRED");
        framed(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(ROUTE + reference).contentType(MediaType.TEXT_PLAIN).content(body(863)), INTEGRATION), 415, "JSON_REQUIRED");
        String large = "{\"amountMinor\":863,\"currency\":\"MYR\",\"pad\":\"" + "x".repeat(4100) + "\"}";
        framed(put(reference, large, INTEGRATION), 413, "BODY_TOO_LARGE");
        for (String invalid : new String[]{"", "null", "[]", "{\"amountMinor\":863,\"currency\":\"MYR\",\"note\":\"SYNTHETIC_SECRET\"}",
                "{\"amountMinor\":863,\"amountMinor\":864,\"currency\":\"MYR\"}", "{\"amountMinor\":\"863\",\"currency\":\"MYR\"}",
                "{\"amountMinor\":8.63,\"currency\":\"MYR\"}", "{\"amountMinor\":863,\"currency\":\"MYR\"} {}",
                "{\"amountMinor\":863,\"currency\":\"MYR\",\"currency\":\"MYR\"}"}) {
            MockHttpServletResponse response = put(reference, invalid, INTEGRATION);
            assertThat(response.getStatus()).as(invalid).isEqualTo(400);
            framed(response, 400, "INVALID_REQUEST");
        }
        for (String currency : new String[]{"", ",\"currency\":\"myr\"", ",\"currency\":\"SGD\"", ",\"currency\":null", ",\"currency\":458", ",\"currency\":[\"MYR\"]"})
            framed(put(reference, "{\"amountMinor\":863" + currency + "}", INTEGRATION), 422, "CURRENCY_INVALID");
        for (String amount : new String[]{"0", "-1", "1000001", "null"})
            framed(put(reference, "{\"amountMinor\":" + amount + ",\"currency\":\"MYR\"}", INTEGRATION), 422, "AMOUNT_INVALID");
        framed(put(reference, "{\"currency\":\"MYR\"}", INTEGRATION), 422, "AMOUNT_INVALID");
        assertThat(service.view().requests()).isEmpty();
        assertThat(put(reference, "{\"currency\":\"MYR\",\"amountMinor\":863}", INTEGRATION).getStatus()).isEqualTo(201);
    }

    @Test void genuineAndroidCandidateIsRefusedOverHttpWhileTheLabelledSyntheticProfileIsAccepted() throws Exception {
        String reference = UUID.randomUUID().toString();
        put(reference, body(863), INTEGRATION);
        Candidate genuine = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 863, Source.TNG_NOTIFICATION_CANDIDATE,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED");
        byte[] before = Files.readAllBytes(file);
        framed(send(post("/api/notification-candidates").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(genuine)),
                NotificationHttpTest.LIVE), 422, "GENUINE_DISABLED");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        Candidate synthetic = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, 863, Source.SYNTHETIC,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
        MockHttpServletResponse accepted = send(post("/api/notification-candidates").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(synthetic)), NotificationHttpTest.LIVE);
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(accepted.getContentAsString()).get("disposition").asText()).isEqualTo("MATCHED");
        assertThat(mapper.readTree(read(reference, INTEGRATION).getContentAsString()).get("status").asText()).isEqualTo("PAYMENT_DETECTED");
    }

    @Test void payerPageUsesSyntheticCopyOnlyInIntegrationMode() throws Exception {
        String synthetic = send(get("/checkout"), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(synthetic).contains("data-checkout-entry=\"private\" data-checkout-mode=\"synthetic\"", "Synthetic integration demo",
                "No payment is needed or possible for this synthetic demo", "Demo instructions", "No payment code exists in synthetic mode");
        assertThat(synthetic).doesNotContain("TNG", "receiving QR", "Payment instructions", "Payment QR", "experimental payment request", "send a payment");
        assertThat(synthetic.getBytes(StandardCharsets.UTF_8)).isEqualTo(CheckoutController.syntheticCheckout(Files.readAllBytes(Path.of("src/main/resources/demo/checkout.html"))));
        String script = send(get("/checkout.js"), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).contains("dataset.checkoutMode === 'synthetic'", "if (synthetic) { hideQr('', ''); session.qrChecked = false; return; }",
                "Potential payment detected — unverified (synthetic)");
        // The synthetic branch returns before the only QR fetch in the script.
        assertThat(script.indexOf("if (synthetic) { hideQr")).isLessThan(script.indexOf("fetch('/api/checkout/' + session.id + '/qr'"));
        assertThat(script.split("/qr'", -1)).hasSize(2);

        configure(NotificationHttpTest.environment());
        String ordinary = send(get("/checkout"), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(ordinary).doesNotContain("data-checkout-mode").contains("independently checks TNG history", "Payment instructions");
        assertThat(ordinary.getBytes(StandardCharsets.UTF_8)).isEqualTo(Files.readAllBytes(Path.of("src/main/resources/demo/checkout.html")));
    }

    @Test void syntheticCopyFailsClosedWhenAReplacedPassageDrifts() {
        assertThatThrownBy(() -> CheckoutController.syntheticCheckout("<html data-checkout-entry=\"private\">changed</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Synthetic checkout copy is out of date");
    }

    @Test void integrationCredentialRequiresDistinctWellFormedValueAndRefusesAConfiguredReceivingQr() {
        for (String invalid : new String[]{"short", "I".repeat(31), "I".repeat(257), "I".repeat(39) + "!", BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE})
            assertThatThrownBy(() -> new BridgeAccess(NotificationHttpTest.environment().withProperty("bridge.integration-token", invalid)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Distinct integration credential of 32 to 256 URL-safe characters required");
        assertThatThrownBy(() -> new BridgeAccess(environment().withProperty("bridge.qr-directory", directory.toString())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Integration mode is synthetic-only; a receiving QR must not be configured")
                .hasMessageNotContaining(directory.toString());
        assertThat(new BridgeAccess(environment()).integrationEnabled()).isTrue();
        assertThat(new BridgeAccess(NotificationHttpTest.environment().withProperty("bridge.qr-directory", directory.toString())).integrationEnabled()).isFalse();
        assertThat(new BridgeAccess(new MockEnvironment().withProperty("bridge.integration-token", INTEGRATION)).integrationEnabled()).isFalse();
    }
}
