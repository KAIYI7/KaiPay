package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Multi-checkout observed mode over the filter and the real application: setting, wire contract, mode line and pages. */
class ObservedMultiCheckoutHttpTest {
    static final String ROUTE = IntegrationHttpTest.ROUTE, INTEGRATION = IntegrationHttpTest.INTEGRATION, LIVE = NotificationHttpTest.LIVE;
    static final String COUNT_KEY = "bridge.integration.observed-checkout-count";
    @TempDir Path directory;
    Path file;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    MockMvc mvc;
    CheckoutHttpTest.MutableClock clock, logClock;
    ByteArrayOutputStream output;

    static MockEnvironment multiEnvironment(String count) { return IntegrationHttpTest.environment().withProperty(COUNT_KEY, count); }

    @BeforeEach void setup() {
        var builder = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new BridgeConfiguration().strictJson().customize(builder);
        mapper = builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        file = directory.resolve("state.json");
        clock = new CheckoutHttpTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        logClock = new CheckoutHttpTest.MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        store = new SnapshotStore(file, mapper);
        configure(multiEnvironment("2"));
    }
    void configure(MockEnvironment environment) {
        BridgeAccess access = new BridgeAccess(environment);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID, 1800, access.integrationEnabled(),
                access.observedAmountMinor, access.observedCheckoutCount);
        output = new ByteArrayOutputStream();
        BridgeLog log = new BridgeLog(new PrintStream(output, true, StandardCharsets.UTF_8), logClock);
        mvc = MockMvcBuilders.standaloneSetup(new BridgeController(service, access), new CheckoutController(service, access, new ReceivingQr("")),
                        new IntegrationController(service))
                .setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper), new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(access, log)).build();
    }
    @AfterEach void close() throws Exception { store.close(); }

    MockHttpServletResponse send(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse();
    }
    MockHttpServletResponse json(MockHttpServletRequestBuilder request, String body, String token) throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(body), token);
    }
    MockHttpServletResponse putCheckout(String reference, int amount) throws Exception { return json(put(ROUTE + reference), IntegrationHttpTest.body(amount), INTEGRATION); }
    String candidate(int amount) throws Exception {
        return mapper.writeValueAsString(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, amount, Source.TNG_NOTIFICATION_CANDIDATE,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "tng.receipt.en.observed.v1", "RULE_MATCHED"));
    }
    MockHttpServletResponse live(String body) throws Exception { return json(post("/api/notification-candidates"), body, LIVE); }
    List<String> lines() { String text = output.toString(StandardCharsets.UTF_8); return text.isEmpty() ? List.of() : List.of(text.split("\\R")); }
    static String observedBody(String reference, String id, int amount, String status, String evidence, String review, int revision) {
        String synthetic = SyntheticWireGoldenTest.integration(reference, id, status, evidence, review, revision).replace("\"amountMinor\":863", "\"amountMinor\":" + amount);
        return synthetic.substring(0, synthetic.length() - 1) + ",\"evidenceMode\":\"OBSERVED_NOTIFICATION\"}";
    }

    // ---- Setting --------------------------------------------------------------------------------------------------

    @Test void countSettingIsStrictlyParsedHasNoCompiledMaximumAndExcludesThePinnedAmount() {
        for (String invalid : new String[]{"0", "-1", "+1", "01", " 1", "1 ", "1.0", "1e3", "one", "１", "1000000000", "9999999999", "2147483648", ","})
            assertThatThrownBy(() -> new BridgeAccess(multiEnvironment(invalid))).as(invalid).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Observed checkout count must be a positive whole number of at most nine digits");
        assertThatThrownBy(() -> new BridgeAccess(multiEnvironment("2").withProperty("bridge.integration.observed-amount-minor", "1")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Set either the observed amount or the observed checkout count, not both");
        assertThatThrownBy(() -> new BridgeAccess(NotificationHttpTest.environment().withProperty(COUNT_KEY, "2")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Observed mode requires the integration credential");
        assertThatThrownBy(() -> new BridgeAccess(BridgeHttpTest.environment().withProperty("bridge.integration-token", INTEGRATION).withProperty(COUNT_KEY, "2")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Observed mode requires the live device credential and UUID");
        assertThatThrownBy(() -> new BridgeAccess(multiEnvironment("2").withProperty("bridge.qr-directory", directory.toString())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Integration mode is synthetic-only; a receiving QR must not be configured");
        for (String valid : new String[]{"1", "2", "500", "999999999"}) {
            BridgeAccess access = new BridgeAccess(multiEnvironment(valid));
            assertThat(access.observedCheckoutCount).as(valid).isEqualTo(Integer.parseInt(valid));
            assertThat(access.observedAmountMinor).isNull();
            assertThat(access.observedMode()).isTrue();
            assertThat(access.multiCheckout()).isTrue();
        }
        // Absent: the single-checkout and synthetic settings are unchanged, and nothing is read while disabled.
        BridgeAccess single = new BridgeAccess(ObservedModeHttpTest.observedEnvironment());
        assertThat(single.observedCheckoutCount).isNull();
        assertThat(single.multiCheckout()).isFalse();
        assertThat(single.observedMode()).isTrue();
        assertThat(new BridgeAccess(IntegrationHttpTest.environment()).observedMode()).isFalse();
        BridgeAccess disabled = new BridgeAccess(new MockEnvironment().withProperty(COUNT_KEY, "2"));
        assertThat(disabled.observedMode()).isFalse();
        assertThat(disabled.observedCheckoutCount).isNull();
    }

    @Test void modeLineNamesTheCountAndLeavesEveryOtherModeLineUnchanged() {
        String multi = new BridgeAccess(multiEnvironment("3")).modeLine(false);
        assertThat(multi).isEqualTo("BRIDGE_MODE mode=OBSERVED amount=unpinned checkouts=3 qr=none live=true integration=true");
        assertThat(new BridgeAccess(multiEnvironment("999999999")).modeLine(false))
                .isEqualTo("BRIDGE_MODE mode=OBSERVED amount=unpinned checkouts=999999999 qr=none live=true integration=true");
        assertThat(new BridgeAccess(ObservedModeHttpTest.observedEnvironment()).modeLine(false))
                .isEqualTo("BRIDGE_MODE mode=OBSERVED amount=1 qr=none live=true integration=true");
        assertThat(new BridgeAccess(IntegrationHttpTest.environment()).modeLine(false))
                .isEqualTo("BRIDGE_MODE mode=SYNTHETIC amount=none qr=none live=true integration=true");
        assertThat(multi).doesNotContain(LIVE, INTEGRATION, BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationCandidateTest.DEVICE_ID.toString());
    }

    // ---- Wire contract --------------------------------------------------------------------------------------------

    @Test void putAndGetBodiesAreTheObservedShapeAndEveryRefusalIsFramedExactlyAndWritesNothing() throws Exception {
        SyntheticWireGoldenTest.exact(putCheckout(UUID.randomUUID().toString(), ObservationService.MAX_AMOUNT + 1), 422, "{\"error\":\"AMOUNT_INVALID\"}");
        assertThat(file).doesNotExist();
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        MockHttpServletResponse created = putCheckout(first, 863);
        assertThat(created.getStatus()).isEqualTo(201);
        String id = mapper.readTree(created.getContentAsString()).get("checkoutId").asText();
        String body = observedBody(first, id, 863, "AWAITING_PAYMENT", "NONE", "null", 1);
        assertThat(created.getContentAsString()).isEqualTo(body);
        SyntheticWireGoldenTest.exact(putCheckout(first, 863), 200, body);
        SyntheticWireGoldenTest.exact(send(get(ROUTE + first), INTEGRATION), 200, body);
        byte[] one = Files.readAllBytes(file);
        SyntheticWireGoldenTest.exact(putCheckout(UUID.randomUUID().toString(), 863), 409, "{\"error\":\"SAME_AMOUNT_HELD\"}");
        SyntheticWireGoldenTest.exact(putCheckout(first, 864), 409, "{\"error\":\"REFERENCE_CONFLICT\"}");
        assertThat(Files.readAllBytes(file)).isEqualTo(one);
        MockHttpServletResponse large = putCheckout(second, ObservationService.MAX_AMOUNT);
        assertThat(large.getStatus()).isEqualTo(201);
        assertThat(mapper.readTree(large.getContentAsString()).get("evidenceMode").asText()).isEqualTo("OBSERVED_NOTIFICATION");
        byte[] two = Files.readAllBytes(file);
        for (int amount : new int[]{1, 2, 863, ObservationService.MAX_AMOUNT})
            SyntheticWireGoldenTest.exact(putCheckout(UUID.randomUUID().toString(), amount), 409, "{\"error\":\"OBSERVED_CHECKOUT_SPENT\"}");
        SyntheticWireGoldenTest.exact(json(post("/api/requests"), "{\"amountMinor\":7}", BridgeHttpTest.RECEIVER), 409, "{\"error\":\"RECEIVER_CREATE_DISABLED\"}");
        SyntheticWireGoldenTest.exact(send(get(ROUTE + UUID.randomUUID()), INTEGRATION), 404, "{\"error\":\"REFERENCE_NOT_FOUND\"}");
        assertThat(Files.readAllBytes(file)).isEqualTo(two);
        assertThat(lines()).isEmpty(); // PUT refusals are not live-device rejections
    }

    @Test void genuineCandidateIsAdmittedOnlyForAHeldAmountAndEachCheckoutReadsItsOwnOutcome() throws Exception {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        putCheckout(first, 863); putCheckout(second, 500);
        byte[] before = Files.readAllBytes(file);
        clock.now = clock.now.plusSeconds(20);
        SyntheticWireGoldenTest.exact(live(candidate(700)), 422, "{\"error\":\"GENUINE_DISABLED\"}");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(lines()).containsExactly("LIVE_REJECTED code=GENUINE_DISABLED");
        MockHttpServletResponse matched = live(candidate(500));
        assertThat(matched.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(matched.getContentAsString()).get("disposition").asText()).isEqualTo("MATCHED");
        JsonNode a = mapper.readTree(send(get(ROUTE + first), INTEGRATION).getContentAsString());
        JsonNode b = mapper.readTree(send(get(ROUTE + second), INTEGRATION).getContentAsString());
        assertThat(a.get("status").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(a.get("evidence").asText()).isEqualTo("NONE");
        assertThat(a.get("revision").asLong()).isEqualTo(1);
        assertThat(b.get("status").asText()).isEqualTo("PAYMENT_DETECTED");
        assertThat(b.get("evidence").asText()).isEqualTo("NOTIFICATION_UNVERIFIED");
        assertThat(b.get("reviewExpiresAt").asText()).isEqualTo("2026-01-01T00:40:00Z");
        assertThat(lines()).containsExactly("LIVE_REJECTED code=GENUINE_DISABLED");
    }

    // ---- Pages ----------------------------------------------------------------------------------------------------

    @Test void receiverPageAndScriptCarryTheMultiCheckoutCopyWhileTheSingleCheckoutModeServesItsOwnBytes() throws Exception {
        byte[] index = Files.readAllBytes(Path.of("src/main/resources/demo/index.html"));
        byte[] script = Files.readAllBytes(Path.of("src/main/resources/demo/demo.js"));
        byte[] checkout = Files.readAllBytes(Path.of("src/main/resources/demo/checkout.html"));
        String receiver = send(get("/"), null).getContentAsString(StandardCharsets.UTF_8);
        String js = send(get("/demo.js"), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(receiver.getBytes(StandardCharsets.UTF_8)).isEqualTo(BridgeController.multiIndex(BridgeController.observedIndex(index)));
        assertThat(js.getBytes(StandardCharsets.UTF_8)).isEqualTo(CheckoutController.rewrite(script, BridgeController.MULTI_SCRIPT, "drift"));
        assertThat(send(get("/checkout"), null).getContentAsByteArray()).isEqualTo(CheckoutController.observedCheckout(checkout));
        assertThat(receiver).contains("<html lang=\"en\" data-bridge-mode=\"observed\">",
                "Review each wallet notification your own phone observed for an integration checkout.",
                "serves a configured number of single-use integration checkouts, and no two of them hold the same amount at once.",
                "Creating checkouts, simulation and manual resolve are disabled in this mode.")
                .doesNotContain("the one integration checkout", "one fixed amount");
        assertThat(js).contains("'No checkout yet. The integration client creates each checkout for this mode.'").doesNotContain("the one checkout");
        ObservedModeHttpTest.noForbidden("multi receiver page", receiver);
        for (String[] passage : BridgeController.MULTI_INDEX) ObservedModeHttpTest.noForbidden("multi replacement", passage[1]);
        for (String[] passage : BridgeController.MULTI_SCRIPT) ObservedModeHttpTest.noForbidden("multi script replacement", passage[1]);
        // Single-checkout observed mode and synthetic mode: byte-identical to before.
        for (MockEnvironment environment : List.of(ObservedModeHttpTest.observedEnvironment(), IntegrationHttpTest.environment())) {
            store.close(); Files.deleteIfExists(file); store = new SnapshotStore(file, mapper);
            configure(environment);
            assertThat(send(get("/demo.js"), null).getContentAsByteArray()).isEqualTo(script);
        }
        assertThat(send(get("/"), null).getContentAsByteArray()).isEqualTo(index);
        store.close(); store = new SnapshotStore(file, mapper);
        configure(ObservedModeHttpTest.observedEnvironment());
        assertThat(send(get("/"), null).getContentAsByteArray()).isEqualTo(BridgeController.observedIndex(index));
    }

    @Test void multiReplacementTablesFailClosedOnDrift() {
        assertThatThrownBy(() -> BridgeController.multiIndex("<html lang=\"en\">changed</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Multi-checkout receiver copy is out of date");
        assertThatThrownBy(() -> CheckoutController.rewrite("const x = 1;".getBytes(StandardCharsets.UTF_8), BridgeController.MULTI_SCRIPT,
                "Multi-checkout receiver script is out of date")).isInstanceOf(IllegalStateException.class).hasMessage("Multi-checkout receiver script is out of date");
    }

    // ---- Real application ------------------------------------------------------------------------------------------

    /** Starts with the pinned classpath configuration; the settings arrive under their environment-variable names. */
    ServletWebServerApplicationContext start(String count, String amount) {
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        for (String arg : List.of("--spring.config.location=classpath:/application.properties",
                "--server.address=127.0.0.1", "--server.port=0", "--server.forward-headers-strategy=NONE", "--bridge.enabled=true",
                "--bridge.receiver-token=" + BridgeHttpTest.RECEIVER, "--bridge.device-token=" + BridgeHttpTest.DEVICE,
                "--bridge.live-device-token=" + LIVE, "--bridge.live-device-id=" + NotificationCandidateTest.DEVICE_ID,
                "--bridge.integration-token=" + INTEGRATION,
                "--bridge.store=" + directory.resolve("app.json"), "--bridge.qr-directory="))
            args.put(arg.substring(0, arg.indexOf('=')), arg);
        if (count != null) args.put("--BRIDGE_INTEGRATION_OBSERVED_CHECKOUT_COUNT", "--BRIDGE_INTEGRATION_OBSERVED_CHECKOUT_COUNT=" + count);
        if (amount != null) args.put("--BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR", "--BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR=" + amount);
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BridgeApplication.class).run(args.values().toArray(String[]::new));
    }

    @Test void environmentVariableStartsMultiCheckoutModeAndStartupRefusesInvalidCombinations() throws Exception {
        assertThatThrownBy(() -> start("0", null)).hasRootCauseMessage("Observed checkout count must be a positive whole number of at most nine digits");
        assertThatThrownBy(() -> start("2", "1")).hasRootCauseMessage("Set either the observed amount or the observed checkout count, not both");
        assertThat(directory.resolve("app.json")).doesNotExist();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var context = ObservedModeApplicationTest.capturing(out, () -> start("2", null)); HttpClient client = HttpClient.newHttpClient()) {
            int port = context.getWebServer().getPort();
            for (int amount : new int[]{863, 12_345})
                assertThat(IntegrationApplicationTest.send(client, port, "PUT", ROUTE + UUID.randomUUID(), INTEGRATION, IntegrationHttpTest.body(amount))
                        .statusCode()).isEqualTo(201);
            var spent = IntegrationApplicationTest.send(client, port, "PUT", ROUTE + UUID.randomUUID(), INTEGRATION, IntegrationHttpTest.body(5));
            assertThat(spent.statusCode()).isEqualTo(409);
            assertThat(spent.body()).isEqualTo("{\"error\":\"OBSERVED_CHECKOUT_SPENT\"}");
        }
        assertThat(ObservedModeApplicationTest.bridgeLines(out))
                .containsExactly("BRIDGE_MODE mode=OBSERVED amount=unpinned checkouts=2 qr=none live=true integration=true");
        byte[] stored = Files.readAllBytes(directory.resolve("app.json"));
        try (var context = start("2", null)) { assertThat(context.getWebServer().getPort()).isPositive(); } // crash restart resumes
        assertThatThrownBy(() -> start("1", null)).hasRootCauseMessage("Observed mode requires an empty or observed-mode store");
        assertThatThrownBy(() -> start(null, "1")).hasRootCauseMessage("Observed mode requires an empty or observed-mode store");
        assertThatThrownBy(() -> start(null, null)).hasRootCauseMessage("Observed-mode store requires observed mode");
        assertThat(Files.readAllBytes(directory.resolve("app.json"))).isEqualTo(stored);
    }
}
