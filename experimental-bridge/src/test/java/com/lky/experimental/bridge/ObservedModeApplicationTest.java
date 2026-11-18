package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/**
 * Observed-evidence mode through the real application over loopback HTTP: the pinned configuration file maps the
 * environment variable, startup refuses every invalid combination, and the operator lines are printed text-free.
 */
class ObservedModeApplicationTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    /** Starts with the pinned classpath configuration; the observed amount arrives under its environment-variable name. */
    ServletWebServerApplicationContext start(String observed, String... overrides) {
        // Keyed, because Spring joins repeated command-line keys with commas instead of replacing them.
        java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
        for (String arg : List.of("--spring.config.location=classpath:/application.properties",
                "--server.address=127.0.0.1", "--server.port=0", "--server.forward-headers-strategy=NONE", "--bridge.enabled=true",
                "--bridge.receiver-token=" + BridgeHttpTest.RECEIVER, "--bridge.device-token=" + BridgeHttpTest.DEVICE,
                "--bridge.live-device-token=" + NotificationHttpTest.LIVE, "--bridge.live-device-id=" + NotificationCandidateTest.DEVICE_ID,
                "--bridge.integration-token=" + IntegrationHttpTest.INTEGRATION,
                "--bridge.store=" + directory.resolve("state.json"), "--bridge.qr-directory="))
            args.put(arg.substring(0, arg.indexOf('=')), arg);
        if (observed != null) args.put("--BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR", "--BRIDGE_INTEGRATION_OBSERVED_AMOUNT_MINOR=" + observed);
        for (String arg : overrides) args.put(arg.substring(0, arg.indexOf('=')), arg);
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BridgeApplication.class).run(args.values().toArray(String[]::new));
    }
    /** Captures standard output while the action runs; BridgeLog binds System.out when the context starts. */
    static <T> T capturing(ByteArrayOutputStream sink, Supplier<T> action) {
        PrintStream original = System.out;
        System.setOut(new PrintStream(sink, true, StandardCharsets.UTF_8));
        try { return action.get(); } finally { System.setOut(original); }
    }
    static List<String> bridgeLines(ByteArrayOutputStream sink) {
        return sink.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("BRIDGE_MODE") || line.startsWith("LIVE_REJECTED")).toList();
    }
    static String genuine(int amount) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"notificationId\":\"" + UUID.randomUUID() + "\",\"sequence\":1,\"observedAt\":\""
                + Instant.now().truncatedTo(ChronoUnit.MILLIS) + "\",\"amountMinor\":" + amount + ",\"source\":\"TNG_NOTIFICATION_CANDIDATE\",\"deviceId\":\""
                + NotificationCandidateTest.DEVICE_ID + "\",\"classification\":\"INCOMING_CANDIDATE\",\"classifierProfile\":\"tng.receipt.en.observed.v1\","
                + "\"evidenceQuality\":\"RULE_MATCHED\"}";
    }

    @Test void pinnedConfigurationStartsObservedModePrintsTheModeLineAndServesTheObservedContract() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String reference = UUID.randomUUID().toString(), route = IntegrationHttpTest.ROUTE + reference;
        try (var context = capturing(out, () -> start("1")); HttpClient client = HttpClient.newHttpClient()) {
            int port = context.getWebServer().getPort();
            PrintStream original = System.out;
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            try {
                var created = IntegrationApplicationTest.send(client, port, "PUT", route, IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(1));
                assertThat(created.statusCode()).isEqualTo(201);
                assertThat(mapper.readTree(created.body()).get("evidenceMode").asText()).isEqualTo("OBSERVED_NOTIFICATION");
                assertThat(IntegrationApplicationTest.send(client, port, "PUT", IntegrationHttpTest.ROUTE + UUID.randomUUID(), IntegrationHttpTest.INTEGRATION,
                        IntegrationHttpTest.body(1)).body()).isEqualTo("{\"error\":\"OBSERVED_CHECKOUT_SPENT\"}");
                // Transport probe shape: a bad token and the companion's synthetic event are distinguishable on stdout.
                assertThat(IntegrationApplicationTest.send(client, port, "POST", "/api/notification-candidates", "wrong-" + NotificationHttpTest.LIVE, genuine(1))
                        .statusCode()).isEqualTo(401);
                String synthetic = genuine(1).replace("TNG_NOTIFICATION_CANDIDATE", "SYNTHETIC").replace("tng.receipt.en.observed.v1", "synthetic.debug.v1")
                        .replace("RULE_MATCHED", "SYNTHETIC");
                assertThat(IntegrationApplicationTest.send(client, port, "POST", "/api/notification-candidates", NotificationHttpTest.LIVE, synthetic).body())
                        .isEqualTo("{\"error\":\"SYNTHETIC_DISABLED\"}");
                var matched = IntegrationApplicationTest.send(client, port, "POST", "/api/notification-candidates", NotificationHttpTest.LIVE, genuine(1));
                assertThat(matched.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(matched.body()).get("disposition").asText()).isEqualTo("MATCHED");
                JsonNode read = mapper.readTree(IntegrationApplicationTest.send(client, port, "GET", route, IntegrationHttpTest.INTEGRATION, null).body());
                assertThat(read.get("status").asText()).isEqualTo("PAYMENT_DETECTED");
                assertThat(read.get("evidence").asText()).isEqualTo("NOTIFICATION_UNVERIFIED");
                assertThat(read.get("evidenceMode").asText()).isEqualTo("OBSERVED_NOTIFICATION");
                String page = IntegrationApplicationTest.send(client, port, "GET", "/", null, null).body();
                assertThat(page).contains("data-bridge-mode=\"observed\"");
                assertThat(IntegrationApplicationTest.send(client, port, "GET", "/checkout", null, null).body()).contains("data-bridge-mode=\"observed\"");
                // A burst past the rate limit; the suppressed count is printed when the context stops.
                for (int i = 0; i < BridgeLog.LIMIT; i++)
                    assertThat(IntegrationApplicationTest.send(client, port, "POST", "/api/notification-candidates", "wrong-" + NotificationHttpTest.LIVE, genuine(1))
                            .statusCode()).isEqualTo(401);
            } finally { System.setOut(original); }
        }
        List<String> expected = new java.util.ArrayList<>(List.of("BRIDGE_MODE mode=OBSERVED amount=1 qr=none live=true integration=true",
                "LIVE_REJECTED code=UNAUTHORIZED", "LIVE_REJECTED code=SYNTHETIC_DISABLED"));
        for (int i = 2; i < BridgeLog.LIMIT; i++) expected.add("LIVE_REJECTED code=UNAUTHORIZED");
        expected.add("LIVE_REJECTED_SUPPRESSED count=2");
        assertThat(bridgeLines(out)).containsExactlyElementsOf(expected);
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain(NotificationHttpTest.LIVE, IntegrationHttpTest.INTEGRATION, BridgeHttpTest.RECEIVER,
                BridgeHttpTest.DEVICE, NotificationCandidateTest.DEVICE_ID.toString(), reference, directory.toString());

        // Crash restart on the same store and amount resumes; any other mode or amount refuses it.
        ByteArrayOutputStream again = new ByteArrayOutputStream();
        try (var context = capturing(again, () -> start("1")); HttpClient client = HttpClient.newHttpClient()) {
            JsonNode read = mapper.readTree(IntegrationApplicationTest.send(client, context.getWebServer().getPort(), "GET", route, IntegrationHttpTest.INTEGRATION, null).body());
            assertThat(read.get("status").asText()).isEqualTo("PAYMENT_DETECTED");
        }
        byte[] stored = Files.readAllBytes(directory.resolve("state.json"));
        assertThatThrownBy(() -> start("2")).hasRootCauseMessage("Invalid experimental store");
        assertThatThrownBy(() -> start(null)).hasRootCauseMessage("Invalid experimental store");
        assertThatThrownBy(() -> start(null, "--bridge.integration-token=")).hasRootCauseMessage("Invalid experimental store");
        assertThat(Files.readAllBytes(directory.resolve("state.json"))).isEqualTo(stored);
    }

    @Test void startupRefusesTheCapQrMissingCredentialsAndCrossModeStores() throws Exception {
        for (String invalid : new String[]{"0", "101", "1.0", "-5"})
            assertThatThrownBy(() -> start(invalid)).as(invalid).hasRootCauseMessage("Observed amount must be a whole number of sen from 1 to 100");
        assertThatThrownBy(() -> start("1", "--bridge.qr-directory=" + Files.createDirectories(directory.resolve("qr"))))
                .hasRootCauseMessage("Integration mode is synthetic-only; a receiving QR must not be configured");
        assertThatThrownBy(() -> start("1", "--bridge.integration-token=")).hasRootCauseMessage("Observed mode requires the integration credential");
        assertThatThrownBy(() -> start("1", "--bridge.live-device-token=", "--bridge.live-device-id="))
                .hasRootCauseMessage("Observed mode requires the live device credential and UUID");
        assertThat(directory.resolve("state.json")).doesNotExist();

        // A synthetic integration store is refused by an observed process.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var context = capturing(out, () -> start(null)); HttpClient client = HttpClient.newHttpClient()) {
            assertThat(IntegrationApplicationTest.send(client, context.getWebServer().getPort(), "PUT", IntegrationHttpTest.ROUTE + UUID.randomUUID(),
                    IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(1)).statusCode()).isEqualTo(201);
        }
        assertThat(bridgeLines(out)).containsExactly("BRIDGE_MODE mode=SYNTHETIC amount=none qr=none live=true integration=true");
        assertThatThrownBy(() -> start("1")).hasRootCauseMessage("Observed mode requires an empty or observed-mode store");
        Files.delete(directory.resolve("state.json"));

        // An observed store with no evidence yet is refused by synthetic and non-integration processes alike.
        try (var context = start("1"); HttpClient client = HttpClient.newHttpClient()) {
            assertThat(IntegrationApplicationTest.send(client, context.getWebServer().getPort(), "PUT", IntegrationHttpTest.ROUTE + UUID.randomUUID(),
                    IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(1)).statusCode()).isEqualTo(201);
        }
        assertThatThrownBy(() -> start(null)).hasRootCauseMessage("Observed-mode store requires observed mode");
        assertThatThrownBy(() -> start(null, "--bridge.integration-token=")).hasRootCauseMessage("Observed-mode store requires observed mode");
        assertThatThrownBy(() -> start("2")).hasRootCauseMessage("Observed mode requires an empty or observed-mode store");
    }

    @Test void nonIntegrationProcessPrintsItsModeLineAndNoRejectionLines() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var context = capturing(out, () -> start(null, "--bridge.integration-token=")); HttpClient client = HttpClient.newHttpClient()) {
            PrintStream original = System.out;
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            try {
                assertThat(IntegrationApplicationTest.send(client, context.getWebServer().getPort(), "POST", "/api/notification-candidates",
                        "wrong-" + NotificationHttpTest.LIVE, genuine(1)).statusCode()).isEqualTo(401);
            } finally { System.setOut(original); }
        }
        assertThat(bridgeLines(out)).containsExactly("BRIDGE_MODE mode=NON_INTEGRATION amount=none qr=none live=true integration=false");
    }
}
