package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** Actual loopback HTTP through the real application: integration routes, payer copy and restart persistence. */
class IntegrationApplicationTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();

    ServletWebServerApplicationContext start(String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.address=127.0.0.1", "--server.port=0",
                "--server.forward-headers-strategy=NONE", "--bridge.enabled=true",
                "--bridge.receiver-token=" + BridgeHttpTest.RECEIVER, "--bridge.device-token=" + BridgeHttpTest.DEVICE,
                "--bridge.live-device-token=" + NotificationHttpTest.LIVE, "--bridge.live-device-id=" + NotificationCandidateTest.DEVICE_ID,
                "--bridge.integration-token=" + IntegrationHttpTest.INTEGRATION,
                "--bridge.store=" + directory.resolve("state.json"), "--bridge.qr-directory="));
        args.addAll(List.of(extra));
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BridgeApplication.class).run(args.toArray(String[]::new));
    }
    static HttpResponse<String> send(HttpClient client, int port, String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void createReplayAndReadBackSurviveARealRestartOverLoopbackHttp() throws Exception {
        String reference = UUID.randomUUID().toString(), route = IntegrationHttpTest.ROUTE + reference;
        String created;
        try (var context = start(); HttpClient client = HttpClient.newHttpClient()) {
            int port = context.getWebServer().getPort();
            var first = send(client, port, "PUT", route, IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(863));
            assertThat(first.statusCode()).isEqualTo(201);
            assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(first.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            created = first.body();
            var replay = send(client, port, "PUT", route, IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(863));
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.body()).isEqualTo(created);
            var missing = send(client, port, "GET", IntegrationHttpTest.ROUTE + UUID.randomUUID(), IntegrationHttpTest.INTEGRATION, null);
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(missing.body()).isEqualTo("{\"error\":\"REFERENCE_NOT_FOUND\"}");
            assertThat(send(client, port, "GET", "/api/state", IntegrationHttpTest.INTEGRATION, null).statusCode()).isEqualTo(401);
            assertThat(send(client, port, "GET", "/api/health", IntegrationHttpTest.INTEGRATION, null).statusCode()).isEqualTo(401);
            assertThat(send(client, port, "GET", route, BridgeHttpTest.RECEIVER, null).statusCode()).isEqualTo(401);
            String page = send(client, port, "GET", "/checkout", null, null).body();
            assertThat(page).contains("data-checkout-mode=\"synthetic\"", "Synthetic integration demo").doesNotContain("TNG");
        }
        JsonNode disk = mapper.readTree(directory.resolve("state.json").toFile()).get("requests").get(0);
        assertThat(disk.get("integrationReference").asText()).isEqualTo(reference);
        assertThat(disk.get("revision").asLong()).isEqualTo(1);
        try (var context = start(); HttpClient client = HttpClient.newHttpClient()) {
            int port = context.getWebServer().getPort();
            var restored = send(client, port, "GET", route, IntegrationHttpTest.INTEGRATION, null);
            assertThat(restored.statusCode()).isEqualTo(200);
            assertThat(restored.body()).isEqualTo(created);
            var replay = send(client, port, "PUT", route, IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(863));
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(replay.body()).isEqualTo(created);
            var conflict = send(client, port, "PUT", route, IntegrationHttpTest.INTEGRATION, IntegrationHttpTest.body(864));
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(conflict.body()).isEqualTo("{\"error\":\"REFERENCE_CONFLICT\"}");
            JsonNode state = mapper.readTree(send(client, port, "GET", "/api/state", BridgeHttpTest.RECEIVER, null).body());
            assertThat(state.get("requests")).hasSize(1);
            assertThat(state.get("requests").get(0).get("id").asText()).isEqualTo(mapper.readTree(created).get("checkoutId").asText());
        }
    }

    @Test void preRevisionSnapshotLoadsThroughTheProductionMapperAndCountsFromZero() throws Exception {
        // Written by the previous binary: no revision or integrationReference fields; an already expired v2 request.
        UUID id = UUID.randomUUID();
        Files.writeString(directory.resolve("state.json"), "{\"requests\":[{\"id\":\"" + id + "\",\"amountMinor\":1234,"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"expiresAt\":\"2026-01-01T00:10:00Z\",\"status\":\"AWAITING_PAYMENT\","
                + "\"observationId\":null,\"lifecycleVersion\":2,\"paymentClosed\":false,\"reviewGrant\":null}],\"observations\":[]}");
        try (var context = start(); HttpClient client = HttpClient.newHttpClient()) {
            int port = context.getWebServer().getPort();
            JsonNode request = mapper.readTree(send(client, port, "GET", "/api/state", BridgeHttpTest.RECEIVER, null).body()).get("requests").get(0);
            assertThat(request.get("id").asText()).isEqualTo(id.toString());
            assertThat(request.get("status").asText()).isEqualTo("EXPIRED");
            assertThat(request.get("revision").asLong()).isEqualTo(1); // loaded as 0, then one lazy expiry
            assertThat(request.get("integrationReference").isNull()).isTrue();
        }
    }

    @Test void startupFailsWhenAReceivingQrIsConfiguredInIntegrationMode() throws Exception {
        Path qr = Files.createDirectories(directory.resolve("qr"));
        assertThatThrownBy(() -> start("--bridge.qr-directory=" + qr)).hasRootCauseMessage("Integration mode is synthetic-only; a receiving QR must not be configured");
        assertThat(directory.resolve("state.json")).doesNotExist();
    }
}
