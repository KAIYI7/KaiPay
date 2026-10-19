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
class CheckoutApplicationTest {
    private static final Path FILE = Path.of("target", "checkout-http-test-" + UUID.randomUUID(), "state.json");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("bridge.store", FILE::toString); }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    HttpResponse<String> send(HttpClient client, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET(); else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void realLoopbackServerServesSeparateCheckoutAndRequiresScopedReadAndManualReceiverCompletion() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/checkout", "/checkout.js", "/demo.css"}) {
                var asset = send(client, path, null, null);
                assertThat(asset.statusCode()).isEqualTo(200);
                assertThat(asset.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(asset.headers().firstValue("Referrer-Policy")).contains("no-referrer");
                assertThat(asset.headers().firstValue("Content-Security-Policy").orElse("")).contains("frame-ancestors 'none'");
                assertThat(asset.body()).doesNotContain(BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE);
            }
            var created = send(client, "/api/requests", BridgeHttpTest.RECEIVER, "{\"amountMinor\":1234}");
            assertThat(created.statusCode()).isEqualTo(200);
            UUID id = mapper.readValue(created.body(), PaymentRequest.class).id();
            var minted = send(client, "/api/requests/" + id + "/payer-access", BridgeHttpTest.RECEIVER, "{}");
            assertThat(minted.statusCode()).isEqualTo(200);
            PayerLink link = mapper.readValue(minted.body(), PayerLink.class);
            assertThat(link.requestId()).isEqualTo(id);
            String path = "/api/checkout/" + id;
            assertThat(send(client, path, null, null).statusCode()).isEqualTo(401);
            assertThat(send(client, path, BridgeHttpTest.RECEIVER, null).statusCode()).isEqualTo(401);
            var awaiting = send(client, path, link.token(), null);
            assertThat(awaiting.statusCode()).isEqualTo(200);
            Checkout checkout = mapper.readValue(awaiting.body(), Checkout.class);
            assertThat(checkout.id()).isEqualTo(id); assertThat(checkout.amountMinor()).isEqualTo(1234);
            assertThat(checkout.status()).isEqualTo(Status.AWAITING_PAYMENT);
            assertThat(send(client, "/api/state", link.token(), null).statusCode()).isEqualTo(401);
            Candidate candidate = new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), 1234, Source.SYNTHETIC,
                    NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
            String event = mapper.writeValueAsString(candidate);
            for (int i = 0; i < 2; i++) assertThat(send(client, "/api/notification-candidates", NotificationHttpTest.LIVE, event).statusCode()).isEqualTo(200);
            var detected = send(client, path, link.token(), null);
            checkout = mapper.readValue(detected.body(), Checkout.class);
            assertThat(checkout.status()).isEqualTo(Status.PAYMENT_DETECTED); assertThat(checkout.evidence()).isEqualTo(PayerEvidence.SYNTHETIC);
            assertThat(detected.body()).doesNotContain(candidate.eventId().toString(), candidate.deviceId().toString(), candidate.notificationId().toString(),
                    "classifierProfile", "evidenceQuality", "observationId", link.token(), BridgeHttpTest.RECEIVER);
            assertThat(send(client, "/api/requests/" + id + "/confirm", BridgeHttpTest.RECEIVER, "{\"historyChecked\":true}").statusCode()).isEqualTo(409);
            for (String action : new String[]{"verify", "confirm"}) {
                assertThat(send(client, "/api/requests/" + id + "/" + action, link.token(), "{\"historyChecked\":true}").statusCode()).isEqualTo(401);
                assertThat(send(client, "/api/requests/" + id + "/" + action, BridgeHttpTest.RECEIVER, "{\"historyChecked\":true}").statusCode()).isEqualTo(200);
                Status expected = action.equals("verify") ? Status.AWAITING_VERIFICATION : Status.MANUALLY_CONFIRMED;
                assertThat(mapper.readValue(send(client, path, link.token(), null).body(), Checkout.class).status()).isEqualTo(expected);
            }
            Snapshot persisted = mapper.readValue(FILE.toFile(), Snapshot.class);
            assertThat(persisted.observations()).hasSize(1); assertThat(persisted.requests().getFirst().status()).isEqualTo(Status.MANUALLY_CONFIRMED);
        }
    }
}
