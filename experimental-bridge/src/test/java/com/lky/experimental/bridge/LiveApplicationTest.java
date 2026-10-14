package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
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
        "bridge.device-token=DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD", "bridge.live-device-token=LLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLLL",
        "bridge.live-device-id=00000000-0000-4000-8000-000000000001"})
class LiveApplicationTest {
    private static final Path FILE = Path.of("target", "live-http-test-" + UUID.randomUUID(), "state.json");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("bridge.store", FILE::toString); }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    HttpResponse<String> send(HttpClient client, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Authorization", "Bearer " + token);
        if (body == null) request.GET(); else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void actualConfiguredServerEnforcesLiveScopeTextTimestampPersistenceAndSeparateReceiverActions() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = send(client, "/api/requests", BridgeHttpTest.RECEIVER, "{\"amountMinor\":1234}");
            assertThat(response.statusCode()).isEqualTo(200);
            UUID id = mapper.readValue(response.body(), PaymentRequest.class).id();
            Candidate candidate = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), 1234, Source.SYNTHETIC,
                    NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
            String event = mapper.writeValueAsString(candidate);
            assertThat(send(client, "/api/notification-candidates", BridgeHttpTest.DEVICE, event).statusCode()).isEqualTo(401);
            assertThat(send(client, "/api/notification-candidates", BridgeHttpTest.RECEIVER, event).statusCode()).isEqualTo(401);
            assertThat(send(client, "/api/notification-candidates", NotificationHttpTest.LIVE, event.replaceFirst("\"observedAt\":\"[^\"]+\"", "\"observedAt\":1767225600")).statusCode()).isEqualTo(422);
            assertThat(send(client, "/api/notification-candidates", NotificationHttpTest.LIVE, event).statusCode()).isEqualTo(200);
            assertThat(send(client, "/api/notification-candidates", NotificationHttpTest.LIVE, event).statusCode()).isEqualTo(200);
            Snapshot persisted = mapper.readValue(FILE.toFile(), Snapshot.class);
            assertThat(persisted.observations()).hasSize(1);
            assertThat(persisted.observations().getFirst().candidate()).isEqualTo(candidate);
            assertThat(send(client, "/api/requests/" + id + "/confirm", NotificationHttpTest.LIVE, "{\"historyChecked\":true}").statusCode()).isEqualTo(401);
            for (String step : new String[]{"verify", "confirm"}) assertThat(send(client, "/api/requests/" + id + "/" + step, BridgeHttpTest.RECEIVER, "{\"historyChecked\":true}").statusCode()).isEqualTo(200);
            assertThat(mapper.readValue(FILE.toFile(), Snapshot.class).requests().getFirst().status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        }
    }
}
