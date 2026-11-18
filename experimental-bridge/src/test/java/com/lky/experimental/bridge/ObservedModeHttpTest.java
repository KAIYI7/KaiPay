package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Observed-evidence mode over the filter: wire contract, error framing, LIVE_REJECTED lines, pages and copy. */
class ObservedModeHttpTest {
    static final String ROUTE = IntegrationHttpTest.ROUTE, INTEGRATION = IntegrationHttpTest.INTEGRATION, LIVE = NotificationHttpTest.LIVE;
    /** The design's never-list for observed-mode pages, compared case-insensitively. */
    static final List<String> FORBIDDEN = List.of("payment detected", "payment confirmed", "confirm payment", "paid", "settled",
            "payment verified", "kaipay payment", "tng payment", "awaiting payment", "potential payment");
    @TempDir Path directory;
    Path file;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    MockMvc mvc;
    CheckoutHttpTest.MutableClock clock, logClock;
    ByteArrayOutputStream output;

    static MockEnvironment observedEnvironment() { return IntegrationHttpTest.environment().withProperty("bridge.integration.observed-amount-minor", "1"); }

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
        configure(observedEnvironment());
    }
    void configure(MockEnvironment environment) {
        BridgeAccess access = new BridgeAccess(environment);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID, 1800, access.integrationEnabled(), access.observedAmountMinor);
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
    String candidate(int amount, Source source) throws Exception {
        return mapper.writeValueAsString(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, clock.now, amount, source, NotificationCandidateTest.DEVICE_ID,
                "INCOMING_CANDIDATE", source == Source.SYNTHETIC ? "synthetic.debug.v1" : "tng.receipt.en.observed.v1",
                source == Source.SYNTHETIC ? "SYNTHETIC" : "RULE_MATCHED"));
    }
    MockHttpServletResponse live(String body) throws Exception { return json(post("/api/notification-candidates"), body, LIVE); }
    List<String> lines() { String text = output.toString(StandardCharsets.UTF_8); return text.isEmpty() ? List.of() : List.of(text.split("\\R")); }
    Set<String> fields(String body) throws Exception {
        Set<String> names = new HashSet<>(); mapper.readTree(body).fieldNames().forEachRemaining(names::add); return names;
    }

    // ---- Wire contract --------------------------------------------------------------------------------------------

    @Test void observedSuccessBodiesAreTheSyntheticBytesPlusOneEvidenceModeKey() throws Exception {
        String reference = UUID.randomUUID().toString();
        MockHttpServletResponse created = putCheckout(reference, 1);
        assertThat(created.getStatus()).isEqualTo(201);
        String id = mapper.readTree(created.getContentAsString()).get("checkoutId").asText();
        String synthetic = SyntheticWireGoldenTest.integration(reference, id, "AWAITING_PAYMENT", "NONE", "null", 1).replace("\"amountMinor\":863", "\"amountMinor\":1");
        String observed = synthetic.substring(0, synthetic.length() - 1) + ",\"evidenceMode\":\"OBSERVED_NOTIFICATION\"}";
        assertThat(created.getContentAsString()).isEqualTo(observed);
        Set<String> allowlist = new HashSet<>(IntegrationHttpTest.ALLOWLIST); allowlist.add("evidenceMode");
        assertThat(fields(created.getContentAsString())).isEqualTo(allowlist);
        SyntheticWireGoldenTest.exact(putCheckout(reference, 1), 200, observed);
        SyntheticWireGoldenTest.exact(send(get(ROUTE + reference), INTEGRATION), 200, observed);
        clock.now = clock.now.plusSeconds(20);
        assertThat(live(candidate(1, Source.TNG_NOTIFICATION_CANDIDATE)).getStatus()).isEqualTo(200);
        String detected = send(get(ROUTE + reference), INTEGRATION).getContentAsString();
        assertThat(fields(detected)).isEqualTo(allowlist);
        assertThat(mapper.readTree(detected).get("evidence").asText()).isEqualTo("NOTIFICATION_UNVERIFIED");
        assertThat(mapper.readTree(detected).get("status").asText()).isEqualTo("PAYMENT_DETECTED");
        assertThat(detected).endsWith(",\"revision\":2,\"evidenceMode\":\"OBSERVED_NOTIFICATION\"}");
        // The receiver state carries the persisted marker; the payer allowlist does not.
        assertThat(mapper.readTree(send(get("/api/state"), BridgeHttpTest.RECEIVER).getContentAsString()).get("requests").get(0).get("evidenceMode").asText())
                .isEqualTo("OBSERVED_NOTIFICATION");
        String token = mapper.readTree(json(post("/api/requests/" + id + "/payer-access"), "{}", BridgeHttpTest.RECEIVER).getContentAsString()).get("token").asText();
        String payer = send(get("/api/checkout/" + id), token).getContentAsString();
        assertThat(fields(payer)).isEqualTo(IntegrationHttpTest.PAYER_ALLOWLIST);
        assertThat(payer).doesNotContain("evidenceMode", reference);
    }

    @Test void newErrorCodesAreFramedExactlyAndCreateNothing() throws Exception {
        SyntheticWireGoldenTest.exact(putCheckout(UUID.randomUUID().toString(), 2), 409, "{\"error\":\"OBSERVED_AMOUNT_MISMATCH\"}");
        assertThat(file).doesNotExist();
        assertThat(putCheckout(UUID.randomUUID().toString(), 1).getStatus()).isEqualTo(201);
        byte[] before = Files.readAllBytes(file);
        SyntheticWireGoldenTest.exact(putCheckout(UUID.randomUUID().toString(), 1), 409, "{\"error\":\"OBSERVED_CHECKOUT_SPENT\"}");
        SyntheticWireGoldenTest.exact(putCheckout(UUID.randomUUID().toString(), 863), 409, "{\"error\":\"OBSERVED_AMOUNT_MISMATCH\"}");
        SyntheticWireGoldenTest.exact(json(post("/api/requests"), "{\"amountMinor\":1}", BridgeHttpTest.RECEIVER), 409, "{\"error\":\"RECEIVER_CREATE_DISABLED\"}");
        String simulated = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"notificationId\":\"" + UUID.randomUUID() + "\",\"sequence\":1,\"observedAt\":\""
                + clock.now + "\",\"amountMinor\":1,\"source\":\"SIMULATED\"}";
        SyntheticWireGoldenTest.exact(json(post("/api/observations"), simulated, BridgeHttpTest.DEVICE), 422, "{\"error\":\"SYNTHETIC_DISABLED\"}");
        SyntheticWireGoldenTest.exact(live(candidate(1, Source.SYNTHETIC)), 422, "{\"error\":\"SYNTHETIC_DISABLED\"}");
        SyntheticWireGoldenTest.exact(live(candidate(2, Source.TNG_NOTIFICATION_CANDIDATE)), 422, "{\"error\":\"GENUINE_DISABLED\"}");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        // The integration credential still reaches nothing else, and cannot ask for a mode in the body.
        SyntheticWireGoldenTest.exact(json(put(ROUTE + UUID.randomUUID()), "{\"amountMinor\":1,\"currency\":\"MYR\",\"evidenceMode\":\"OBSERVED_NOTIFICATION\"}", INTEGRATION),
                400, "{\"error\":\"INVALID_REQUEST\"}");
        for (MockHttpServletRequestBuilder route : List.of(get("/api/state"), get("/api/health"),
                post("/api/notification-candidates").contentType(MediaType.APPLICATION_JSON).content(candidate(1, Source.TNG_NOTIFICATION_CANDIDATE))))
            SyntheticWireGoldenTest.exact(send(route, INTEGRATION), 401, "{\"error\":\"UNAUTHORIZED\"}");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    // ---- LIVE_REJECTED --------------------------------------------------------------------------------------------

    @Test void everyRejectedLivePostPrintsOnlyItsAllowlistedCode() throws Exception {
        String reference = UUID.randomUUID().toString();
        putCheckout(reference, 1);
        assertThat(lines()).isEmpty();
        json(post("/api/notification-candidates"), candidate(1, Source.SYNTHETIC), "wrong-" + LIVE);
        live(candidate(1, Source.SYNTHETIC));
        live(candidate(2, Source.TNG_NOTIFICATION_CANDIDATE));
        send(post("/api/notification-candidates").contentType(MediaType.TEXT_PLAIN).content("{}"), LIVE);
        live("{\"pad\":\"" + "x".repeat(4200) + "\"}");
        json(post("/api/notification-candidates/" + UUID.randomUUID() + "/invalidate"), "{\"reason\":\"NOTIFICATION_CHANGED\"}", LIVE);
        json(post("/api/notification-candidates/" + UUID.randomUUID() + "/invalidate"), "{\"reason\":\"NOTIFICATION_CHANGED\"}", null);
        assertThat(lines()).containsExactly("LIVE_REJECTED code=UNAUTHORIZED", "LIVE_REJECTED code=SYNTHETIC_DISABLED",
                "LIVE_REJECTED code=GENUINE_DISABLED", "LIVE_REJECTED code=JSON_REQUIRED", "LIVE_REJECTED code=BODY_TOO_LARGE",
                "LIVE_REJECTED code=OBSERVATION_NOT_FOUND", "LIVE_REJECTED code=UNAUTHORIZED");
        // Successes, GETs, non-canonical paths and rejections on every other route print nothing.
        output.reset();
        clock.now = clock.now.plusSeconds(20);
        String admitted = candidate(1, Source.TNG_NOTIFICATION_CANDIDATE);
        assertThat(live(admitted).getStatus()).isEqualTo(200);
        assertThat(live(admitted).getStatus()).isEqualTo(200); // exact retry
        send(get("/api/notification-candidates"), LIVE);
        json(post("/api/notification-candidates/not-a-uuid/invalidate"), "{}", LIVE);
        json(post("/api/notification-candidates/"), "{}", LIVE);
        putCheckout(UUID.randomUUID().toString(), 2);
        json(post("/api/requests"), "{\"amountMinor\":1}", BridgeHttpTest.RECEIVER);
        json(post("/api/observations"), "{}", BridgeHttpTest.DEVICE);
        json(post("/api/observations/" + UUID.randomUUID() + "/resolve"), "{}", "bad");
        assertThat(lines()).isEmpty();
    }

    @Test void malformedAndCrLfLadenBodiesPrintOnlyTheConstant() throws Exception {
        String injected = "LIVE_REJECTED code=UNAUTHORIZED";
        String secret = "SECRET-" + UUID.randomUUID();
        List<String> bodies = List.of(
                "{\"eventId\":\"\r\n" + injected + "\r\n\"}",
                "{\"source\":\"" + secret + "\\r\\n" + injected + "\"}",
                "{\"eventId\":\"" + UUID.randomUUID() + "\",\"notificationId\":\"x\r\nBRIDGE_MODE mode=OBSERVED\",\"sequence\":1}",
                "not json\r\n" + injected + "\n" + secret,
                "{\"amountMinor\":1,\"amountMinor\":2}",
                "{\"observedAt\":\"" + secret + "\"}");
        for (String body : bodies) live(body);
        List<String> printed = lines();
        assertThat(printed).hasSize(bodies.size());
        assertThat(printed).allSatisfy(line -> assertThat(line).matches("LIVE_REJECTED code=[A-Z_]+"));
        assertThat(printed).isSubsetOf("LIVE_REJECTED code=INVALID_REQUEST", "LIVE_REJECTED code=EVENT_INVALID", "LIVE_REJECTED code=EVENT_TIME_INVALID")
                .contains("LIVE_REJECTED code=INVALID_REQUEST");
        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(secret, "BRIDGE_MODE", "x\r", LIVE, INTEGRATION, BridgeHttpTest.RECEIVER, "/api/");
        // A header value with CR/LF-like content or an event ID in the path is never printed either.
        output.reset();
        UUID event = UUID.randomUUID();
        json(post("/api/notification-candidates/" + event + "/invalidate"), "{\"reason\":\"" + secret + "\"}", LIVE);
        assertThat(lines()).containsExactly("LIVE_REJECTED code=INVALIDATION_INVALID");
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(event.toString(), secret);
    }

    @Test void codesOutsideTheCompiledAllowlistPrintAsUnlistedAndTheRateLimitCountsTheRest() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BridgeLog log = new BridgeLog(new PrintStream(bytes, true, StandardCharsets.UTF_8), logClock);
        log.liveRejected("SOMETHING\r\nINJECTED"); log.liveRejected(null); log.liveRejected("GENUINE_DISABLED");
        for (int i = 0; i < BridgeLog.LIMIT + 5; i++) log.liveRejected("UNAUTHORIZED");
        List<String> printed = List.of(bytes.toString(StandardCharsets.UTF_8).split("\\R"));
        assertThat(printed).hasSize(BridgeLog.LIMIT);
        assertThat(printed.subList(0, 3)).containsExactly("LIVE_REJECTED code=UNLISTED", "LIVE_REJECTED code=UNLISTED", "LIVE_REJECTED code=GENUINE_DISABLED");
        assertThat(printed.subList(3, BridgeLog.LIMIT)).containsOnly("LIVE_REJECTED code=UNAUTHORIZED");
        logClock.now = logClock.now.plusSeconds(59);
        log.liveRejected("UNAUTHORIZED");
        assertThat(bytes.toString(StandardCharsets.UTF_8).split("\\R")).hasSize(BridgeLog.LIMIT);
        logClock.now = logClock.now.plusSeconds(1);
        log.liveRejected("SYNTHETIC_DISABLED");
        List<String> all = List.of(bytes.toString(StandardCharsets.UTF_8).split("\\R"));
        assertThat(all.subList(BridgeLog.LIMIT, all.size())).containsExactly("LIVE_REJECTED_SUPPRESSED count=9", "LIVE_REJECTED code=SYNTHETIC_DISABLED");
        assertThat(String.join("\n", all)).doesNotContain("INJECTED");
        // A burst followed by silence: the count prints when the bridge stops (flush), once.
        for (int i = 0; i < BridgeLog.LIMIT + 3; i++) log.liveRejected("UNAUTHORIZED");
        log.flush(); log.flush();
        List<String> stopped = List.of(bytes.toString(StandardCharsets.UTF_8).split("\\R"));
        assertThat(stopped.getLast()).isEqualTo("LIVE_REJECTED_SUPPRESSED count=4");
        assertThat(stopped.stream().filter(line -> line.startsWith("LIVE_REJECTED_SUPPRESSED"))).hasSize(2);
        // Every allowlisted code is a fixed upper-case token.
        assertThat(BridgeLog.LIVE_CODES).allSatisfy(code -> assertThat(code).matches("[A-Z_]+"));
    }

    @Test void syntheticAndNonIntegrationProcessesPrintNoRejectionLines() throws Exception {
        for (MockEnvironment environment : List.of(IntegrationHttpTest.environment(), NotificationHttpTest.environment())) {
            configure(environment);
            json(post("/api/notification-candidates"), candidate(1, Source.SYNTHETIC), "wrong-" + LIVE);
            live(candidate(2, Source.TNG_NOTIFICATION_CANDIDATE));
            live("not json");
            assertThat(lines()).isEmpty();
        }
    }

    @Test void effectiveModeLineNamesTheModeWithoutAnyCredentialOrIdentifier() {
        String observed = new BridgeAccess(observedEnvironment()).modeLine(false);
        assertThat(observed).isEqualTo("BRIDGE_MODE mode=OBSERVED amount=1 qr=none live=true integration=true");
        assertThat(new BridgeAccess(IntegrationHttpTest.environment()).modeLine(false))
                .isEqualTo("BRIDGE_MODE mode=SYNTHETIC amount=none qr=none live=true integration=true");
        assertThat(new BridgeAccess(BridgeHttpTest.environment()).modeLine(true))
                .isEqualTo("BRIDGE_MODE mode=NON_INTEGRATION amount=none qr=configured live=false integration=false");
        assertThat(new BridgeAccess(IntegrationHttpTest.environment().withProperty("bridge.integration.observed-amount-minor", "100")).modeLine(false))
                .isEqualTo("BRIDGE_MODE mode=OBSERVED amount=100 qr=none live=true integration=true");
        assertThat(observed).doesNotContain(LIVE, INTEGRATION, BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationCandidateTest.DEVICE_ID.toString());
    }

    @Test void observedSettingIsStrictlyParsedCappedAndRequiresIntegrationAndLiveDelivery() {
        for (String invalid : new String[]{"0", "101", "1000", "-1", "+1", "01", " 1", "1 ", "1.0", "1e2", "one", "１"})
            assertThatThrownBy(() -> new BridgeAccess(IntegrationHttpTest.environment().withProperty("bridge.integration.observed-amount-minor", invalid)))
                    .as(invalid).isInstanceOf(IllegalStateException.class).hasMessage("Observed amount must be a whole number of sen from 1 to 100");
        assertThatThrownBy(() -> new BridgeAccess(NotificationHttpTest.environment().withProperty("bridge.integration.observed-amount-minor", "1")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Observed mode requires the integration credential");
        assertThatThrownBy(() -> new BridgeAccess(BridgeHttpTest.environment().withProperty("bridge.integration-token", INTEGRATION)
                .withProperty("bridge.integration.observed-amount-minor", "1")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Observed mode requires the live device credential and UUID");
        assertThatThrownBy(() -> new BridgeAccess(observedEnvironment().withProperty("bridge.qr-directory", directory.toString())))
                .isInstanceOf(IllegalStateException.class).hasMessage("Integration mode is synthetic-only; a receiving QR must not be configured");
        assertThat(new BridgeAccess(observedEnvironment()).observedAmountMinor).isEqualTo(1);
        assertThat(new BridgeAccess(IntegrationHttpTest.environment()).observedMode()).isFalse();
        assertThat(new BridgeAccess(new MockEnvironment().withProperty("bridge.integration.observed-amount-minor", "1")).observedMode()).isFalse(); // disabled
    }

    // ---- Pages and copy -------------------------------------------------------------------------------------------

    static String lower(String text) { return text.toLowerCase(Locale.ROOT); }
    static void noForbidden(String where, String text) {
        for (String phrase : FORBIDDEN) assertThat(lower(text)).as(where + " contains '" + phrase + "'").doesNotContain(phrase);
    }

    @Test void observedPagesAreServerRewrittenWithTheModeAttributeHiddenPanelsAndNoForbiddenPhrase() throws Exception {
        byte[] index = Files.readAllBytes(Path.of("src/main/resources/demo/index.html"));
        byte[] checkout = Files.readAllBytes(Path.of("src/main/resources/demo/checkout.html"));
        String receiver = send(get("/"), null).getContentAsString(StandardCharsets.UTF_8);
        String payer = send(get("/checkout"), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(receiver.getBytes(StandardCharsets.UTF_8)).isEqualTo(BridgeController.observedIndex(index));
        assertThat(payer.getBytes(StandardCharsets.UTF_8)).isEqualTo(CheckoutController.observedCheckout(checkout));
        assertThat(receiver).contains("<html lang=\"en\" data-bridge-mode=\"observed\">", "aria-labelledby=\"create-title\" hidden>",
                "<details class=\"panel\" hidden><summary>Developer tools", "<label class=\"hidden\">Approved remote payer origin", "Observed-evidence mode",
                "Creating checkouts, simulation and manual resolve are disabled in this mode.");
        assertThat(payer).contains("data-checkout-entry=\"private\" data-bridge-mode=\"observed\"",
                "This page does not request or take payment. It shows what the receiver's phone observed. Transfer at most once; if you already sent money, do not send again.",
                "No payment code on this page").doesNotContain("data-checkout-mode", "receiving QR", "Payment instructions", "QR");
        noForbidden("receiver page", receiver);
        noForbidden("payer page", payer);
        for (String[] passage : BridgeController.OBSERVED_INDEX) noForbidden("receiver replacement", passage[1]);
        for (String[] passage : CheckoutController.OBSERVED_COPY) noForbidden("payer replacement", passage[1]);
    }

    @Test void observedReplacementTablesFailClosedOnDriftOrDuplication() {
        assertThatThrownBy(() -> CheckoutController.observedCheckout("<html data-checkout-entry=\"private\">changed</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Observed checkout copy is out of date");
        assertThatThrownBy(() -> BridgeController.observedIndex("<html lang=\"en\">changed</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Observed receiver copy is out of date");
        String twice = "<html lang=\"en\"><html lang=\"en\">";
        assertThatThrownBy(() -> CheckoutController.rewrite(twice.getBytes(StandardCharsets.UTF_8), new String[][]{{"<html lang=\"en\">", "x"}}, "drift"))
                .isInstanceOf(IllegalStateException.class).hasMessage("drift");
    }

    static String block(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        assertThat(from).as(start).isGreaterThanOrEqualTo(0);
        assertThat(to).as(end).isGreaterThan(from);
        return source.substring(from, to);
    }

    @Test void observedCopyInventoryCarriesTheDesignWordingAndNoForbiddenPhrase() throws Exception {
        String payer = send(get("/checkout.js"), null).getContentAsString(StandardCharsets.UTF_8);
        String receiver = send(get("/demo.js"), null).getContentAsString(StandardCharsets.UTF_8);
        String states = block(payer, "const observedStates = {", "};");
        String names = block(receiver, "const observedNames = {", "};");
        for (String text : List.of(states, names)) {
            noForbidden("observed status names", text);
            assertThat(text).contains("Waiting for the receiver's own wallet notification", "Notification observed — unverified",
                    "Manually confirmed by the receiver — notification unverified", "Ambiguous — not confirmed", "Ended without confirmation");
        }
        assertThat(payer).contains("const observed = privateEntry && !synthetic && document.documentElement.dataset.bridgeMode === 'observed';",
                "'Notification window closed — receiver review pending (unverified)'", "'Wallet notification observed · UNVERIFIED.",
                "if (observed && request.evidence === 'SYNTHETIC')");
        assertThat(receiver).contains("const observedMode = document.documentElement.dataset.bridgeMode === 'observed';",
                "'Notification window closed — receiver review pending (unverified)'", "'Wallet notification observed · UNVERIFIED'",
                "'I checked my own wallet history — confirm manually'", "if (!observedMode && simulationOpen && !(evidence && !synthetic))",
                "if (!observedMode && candidate.source !== 'SIMULATED' && ['UNMATCHED', 'AMBIGUOUS'].includes(item.disposition))",
                "const configured = observedMode ? '' : byId('payer-origin').value.trim();",
                "if (observedMode) { byId('create').disabled = true; byId('observe').disabled = true; }");
        // The genuine attestation checkbox text is kept, as the design requires.
        assertThat(receiver).contains("I independently checked actual TNG transaction history: the incoming transaction exists");
        // Every observed-only literal on the observed branches is free of forbidden phrases.
        Matcher literals = Pattern.compile("observed(?:Mode)? \\? (?:\\(\\w+ \\? )?('(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")").matcher(payer + receiver);
        int count = 0;
        while (literals.find()) { noForbidden("observed branch literal", literals.group(1)); count++; }
        assertThat(count).isGreaterThanOrEqualTo(10);
    }

    @Test void payerRecheckRunsOnVisibilityFocusAndOnlineAndNoAwaitCanHoldBusyPastTheAbort() throws Exception {
        String script = send(get("/checkout.js"), null).getContentAsString(StandardCharsets.UTF_8);
        assertThat(script).contains("document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void refresh(true); });",
                "window.addEventListener('focus', () => { void refresh(true); });", "window.addEventListener('online', () => { void refresh(true); });",
                "if (session.busy) { if (queue === true) session.again = true; return; }", "timer = setInterval(refresh, 3000);", "if (session.again && current(session)) void refresh();",
                "const timeout = setTimeout(cancel, 10000);");
        // The abort helper rejects on abort, including an already-aborted signal.
        String helper = block(script, "const abortable = (promise, signal) =>", "\n  });");
        assertThat(helper).contains("if (signal.aborted) { stop(); return; }", "signal.addEventListener('abort', stop, {once: true});",
                "reject(new DOMException('Attempt aborted', 'AbortError'))");
        // Every await in the file, and each one is bounded by the attempt's signal.
        List<String> awaits = new ArrayList<>();
        Matcher await = Pattern.compile("await\\s+([^;]+);").matcher(script);
        while (await.find()) awaits.add(await.group(1));
        assertThat(awaits).hasSize(6);
        assertThat(awaits.stream().filter(a -> a.startsWith("fetch(")).toList()).hasSize(2).allSatisfy(a -> assertThat(a).containsPattern("signal(?::\\s*attempt\\.signal)?\\}\\)$"));
        assertThat(awaits).contains("result.blob()", "result.json()", "abortable(image.decode(), signal)", "loadQr(request, session, attempt.signal)");
        assertThat(script).doesNotContain("await image.decode()");
        assertThat(script.split("async function", -1)).hasSize(3); // loadQr and refresh only
        // The busy flag is released in finally, after the abort timer is cleared.
        String tail = block(script, "    } finally {\n      clearTimeout(timeout);", "  }\n");
        assertThat(tail).contains("session.busy = false;");
    }
}
