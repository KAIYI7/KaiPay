package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import static com.lky.experimental.bridge.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bridge.enabled=true", "bridge.receiver-token=RRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRRR",
        "bridge.device-token=DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"})
class EnabledApplicationTest {
    private static final Path FILE = Path.of("target", "http-test-" + UUID.randomUUID(), "state.json");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("bridge.store", FILE::toString); }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private HttpResponse<String> send(HttpClient client, String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void realServerUsesFeatureAuthStrictJsonPersistenceAndSeparateManualSteps() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(send(client, "/", null, null).body()).contains("Placeholder QR", "UNVERIFIED");
            assertThat(send(client, "/demo.js", null, null).statusCode()).isEqualTo(200);
            assertThat(send(client, "/api/state", null, null).statusCode()).isEqualTo(401);
            assertThat(send(client, "/api/state", BridgeHttpTest.DEVICE, null).statusCode()).isEqualTo(401);
            for (String invalid : new String[]{"{\"amountMinor\":1.5}", "{\"amountMinor\":\"1\"}",
                    "{\"amountMinor\":1,\"text\":\"SYNTHETIC_SECRET\"}", "{\"amountMinor\":1,\"amountMinor\":2}", "{\"amountMinor\":1} {}"}) {
                var response = send(client, "/api/requests", BridgeHttpTest.RECEIVER, invalid);
                assertThat(response.statusCode()).isEqualTo(400);
                assertThat(response.body()).isEqualTo("{\"error\":\"INVALID_REQUEST\"}");
            }
            var created = send(client, "/api/requests", BridgeHttpTest.RECEIVER, "{\"amountMinor\":1234}");
            assertThat(created.statusCode()).isEqualTo(200);
            PaymentRequest request = mapper.readValue(created.body(), PaymentRequest.class);
            String event = mapper.writeValueAsString(new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), 1234, Source.SIMULATED));
            assertThat(send(client, "/api/observations", BridgeHttpTest.DEVICE, event.replace("\"SIMULATED\"", "0")).statusCode()).isEqualTo(400);
            assertThat(send(client, "/api/observations", BridgeHttpTest.RECEIVER, event).statusCode()).isEqualTo(401);
            assertThat(send(client, "/api/observations", BridgeHttpTest.DEVICE, event).statusCode()).isEqualTo(200);
            Snapshot snapshot = mapper.readValue(send(client, "/api/state", BridgeHttpTest.RECEIVER, null).body(), Snapshot.class);
            assertThat(snapshot.requests().getFirst().status()).isEqualTo(Status.PAYMENT_DETECTED);
            String route = "/api/requests/" + request.id();
            assertThat(send(client, route + "/confirm", BridgeHttpTest.DEVICE, "{\"historyChecked\":true}").statusCode()).isEqualTo(401);
            assertThat(send(client, route + "/confirm", BridgeHttpTest.RECEIVER, "{\"historyChecked\":true}").statusCode()).isEqualTo(409);
            assertThat(send(client, route + "/verify", BridgeHttpTest.RECEIVER, "{\"historyChecked\":true}").statusCode()).isEqualTo(200);
            assertThat(send(client, route + "/confirm", BridgeHttpTest.RECEIVER, "{\"historyChecked\":true}").statusCode()).isEqualTo(200);
            Snapshot persisted = mapper.readValue(FILE.toFile(), Snapshot.class);
            assertThat(persisted.requests().getFirst().status()).isEqualTo(Status.MANUALLY_CONFIRMED);
            assertThat(persisted.observations()).hasSize(1);
            assertThat(send(client, "/api/observations", BridgeHttpTest.DEVICE, event).statusCode()).isEqualTo(200);
        }
    }
}
