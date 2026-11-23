package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import static com.lky.experimental.bridge.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Actual assembled sockets, no mock gateway/backend or financial services. */
class PayerGatewayApplicationTest {
    @TempDir Path directory;
    static final MutableClock CLOCK = new MutableClock();
    static final MutableClock ACCESS_CLOCK = new MutableClock();
    private static final String RECEIVER = "R".repeat(40), SIMULATION = "D".repeat(40), LIVE = "L".repeat(40);
    private static final UUID DEVICE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private int backendPort;
    private ObjectMapper mapper;
    private HttpClient client;

    private ServletWebServerApplicationContext backend(int port) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BridgeApplication.class, Controlled.class).run(
                "--server.address=127.0.0.1", "--server.port=" + port, "--bridge.enabled=true", "--bridge.store=" + directory.resolve("state.json"),
                "--bridge.receiver-token=" + RECEIVER, "--bridge.device-token=" + SIMULATION, "--bridge.live-device-token=" + LIVE,
                "--bridge.live-device-id=" + DEVICE, "--bridge.qr-directory=" + directory.resolve("placeholder"));
    }
    private HttpResponse<String> send(int port, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(8));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET(); else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private PaymentRequest create(int amount) throws Exception {
        var response = send(backendPort, "/api/requests", RECEIVER, new CreateRequest(amount));
        assertThat(response.statusCode()).isEqualTo(200); return mapper.readValue(response.body(), PaymentRequest.class);
    }
    private String issue(UUID id) throws Exception {
        var response = send(backendPort, "/api/requests/" + id + "/payer-access", RECEIVER, java.util.Map.of());
        assertThat(response.statusCode()).isEqualTo(200); return mapper.readValue(response.body(), PayerLink.class).token();
    }
    private Checkout status(int port, UUID id, String token, Status expected) throws Exception {
        var response = send(port, "/api/checkout/" + id, token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("observationId", "eventId", "deviceId", "classifierProfile", "historyChecked", RECEIVER, LIVE, token);
        var checkout = mapper.readValue(response.body(), Checkout.class); assertThat(checkout.status()).isEqualTo(expected); return checkout;
    }
    private Candidate candidate(int amount) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, CLOCK.instant(), amount, Source.SYNTHETIC,
                DEVICE, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
    }
    private void qrDenied(int port, UUID id, String token) throws Exception {
        assertThat(send(port, "/api/checkout/" + id + "/qr", token, null).statusCode()).isEqualTo(409);
    }

    private void preparePlaceholder() throws Exception {
        java.nio.file.Files.createDirectory(directory.resolve("placeholder"));
        var placeholder = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = placeholder.createGraphics();
        graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 128, 128);
        graphics.setColor(java.awt.Color.BLACK); graphics.drawString("NOT PAYABLE", 10, 64); graphics.dispose();
        javax.imageio.ImageIO.write(placeholder, "png", directory.resolve("placeholder/receiving.png").toFile());
    }

    @Test void privateBackendAndGatewaySelectExplicitEntryModesWithSameSecurityBoundary() throws Exception {
        CLOCK.now = Instant.parse("2026-01-01T00:00:00Z");
        ACCESS_CLOCK.now = CLOCK.now;
        preparePlaceholder();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        try (var context = backend(0);
             var gateway = new PayerGateway(0, context.getWebServer().getPort())) {
            backendPort = context.getWebServer().getPort();
            mapper = context.getBean(ObjectMapper.class);
            gateway.start();
            var privateDocument = send(backendPort, "/checkout", null, null);
            var publicDocument = send(gateway.port(), "/checkout", null, null);
            assertThat(privateDocument.statusCode()).isEqualTo(200);
            assertThat(privateDocument.body()).contains("data-checkout-entry=\"private\"", "checkout-access-form");
            assertThat(publicDocument.statusCode()).isEqualTo(200);
            assertThat(publicDocument.body()).contains("data-checkout-entry=\"code\"", "checkout-access-form")
                    .doesNotContain("data-checkout-entry=\"private\"");
            assertThat(publicDocument.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(publicDocument.headers().firstValue("Referrer-Policy")).contains("no-referrer");
            assertThat(publicDocument.headers().firstValue("Content-Security-Policy").orElse("")).contains("script-src 'self'", "form-action 'none'");
            assertThat(publicDocument.headers().firstValue("Set-Cookie")).isEmpty();
            assertThat(send(gateway.port(), "/checkout?code=invalid", null, null).statusCode()).isEqualTo(404);
            assertThat(send(gateway.port(), "/api/state", RECEIVER, null).statusCode()).isEqualTo(404);
        } finally { client.close(); }
    }

    @Test void assembledLifecycleOutageRestartRevocationAndRoleSeparation() throws Exception {
        CLOCK.now = Instant.parse("2026-01-01T00:00:00Z");
        ACCESS_CLOCK.now = CLOCK.now;
        preparePlaceholder();
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var context = backend(0);
        backendPort = context.getWebServer().getPort(); mapper = context.getBean(ObjectMapper.class);
        PayerGateway gateway = new PayerGateway(0, backendPort); gateway.start();
        try {
            int port = gateway.port();
            var request = create(1234); String token = issue(request.id()); String path = "/api/checkout/" + request.id();
            status(port, request.id(), token, Status.AWAITING_PAYMENT);
            assertThat(send(port, path, null, null).statusCode()).isEqualTo(401);
            for (String other : new String[]{RECEIVER, SIMULATION, LIVE}) assertThat(send(port, path, other, null).statusCode()).isEqualTo(401);
            var qr = send(port, path + "/qr", token, null);
            assertThat(qr.statusCode()).isEqualTo(200); // manufactured image, no payment payload
            assertThat(qr.headers().firstValue("Content-Type")).contains("image/png");
            var other = create(2345); assertThat(send(port, "/api/checkout/" + other.id(), token, null).statusCode()).isEqualTo(401);
            Candidate event = candidate(1234);
            assertThat(send(backendPort, "/api/notification-candidates", LIVE, event).statusCode()).isEqualTo(200);
            status(port, request.id(), token, Status.PAYMENT_DETECTED); qrDenied(port, request.id(), token);
            // Public credentials cannot mutate even if the actual receiver secret is supplied.
            for (String action : new String[]{"verify", "confirm", "payer-access", "payer-revoke"})
                assertThat(send(port, "/api/requests/" + request.id() + "/" + action, RECEIVER, new Confirmation(true, event.eventId())).statusCode()).isEqualTo(404);
            assertThat(send(backendPort, "/api/requests/" + request.id() + "/confirm", RECEIVER, new Confirmation(true, event.eventId())).statusCode()).isEqualTo(409);
            for (String action : new String[]{"verify", "confirm"})
                assertThat(send(backendPort, "/api/requests/" + request.id() + "/" + action, RECEIVER, new Confirmation(true, event.eventId())).statusCode()).isEqualTo(200);
            token = issue(request.id()); status(port, request.id(), token, Status.MANUALLY_CONFIRMED); qrDenied(port, request.id(), token);

            CLOCK.now = request.expiresAt();
            String expired = issue(other.id()); status(port, other.id(), expired, Status.EXPIRED); qrDenied(port, other.id(), expired);
            var review = create(3456); String reviewToken = issue(review.id()); var reviewEvent = candidate(3456);
            assertThat(send(backendPort, "/api/notification-candidates", LIVE, reviewEvent).statusCode()).isEqualTo(200);
            CLOCK.now = review.expiresAt();
            var pending = status(port, review.id(), reviewToken, Status.PAYMENT_DETECTED); assertThat(pending.paymentWindowClosed()).isTrue();
            assertThat(pending.reviewAvailable()).isTrue(); qrDenied(port, review.id(), reviewToken);
            CLOCK.now = pending.reviewExpiresAt();
            status(port, review.id(), reviewToken, Status.REVIEW_EXPIRED); qrDenied(port, review.id(), reviewToken);
            assertThat(send(backendPort, "/api/requests/" + review.id() + "/verify", RECEIVER, new Confirmation(true, reviewEvent.eventId())).statusCode()).isEqualTo(409);

            var recover = create(4567); String oldToken = issue(recover.id());
            String freshToken = issue(recover.id());
            assertThat(send(port, "/api/checkout/" + recover.id(), oldToken, null).statusCode()).isEqualTo(401);
            assertThat(send(backendPort, "/api/requests/" + recover.id() + "/payer-revoke", RECEIVER, java.util.Map.of()).statusCode()).isEqualTo(204);
            assertThat(send(port, "/api/checkout/" + recover.id(), freshToken, null).statusCode()).isEqualTo(401);
            freshToken = issue(recover.id());
            // Gateway restart does not revoke backend-held grants or change financial state.
            gateway.close(); gateway = new PayerGateway(port, backendPort); gateway.start();
            status(port, recover.id(), freshToken, Status.AWAITING_PAYMENT);
            // Real receiver outage fails safely; restarting persists requests but revokes all grants.
            context.close();
            assertThat(send(port, "/api/checkout/" + recover.id(), freshToken, null).statusCode()).isEqualTo(503);
            context = backend(backendPort); mapper = context.getBean(ObjectMapper.class);
            assertThat(send(port, "/api/checkout/" + recover.id(), freshToken, null).statusCode()).isEqualTo(401);
            freshToken = issue(recover.id()); status(port, recover.id(), freshToken, Status.AWAITING_PAYMENT);
            assertThat(send(port, "/api/checkout/" + recover.id() + "/qr", freshToken, null).statusCode()).isEqualTo(200);
            ACCESS_CLOCK.now = ACCESS_CLOCK.now.plus(PayerTokens.LIFETIME);
            assertThat(send(port, "/api/checkout/" + recover.id(), freshToken, null).statusCode()).isEqualTo(401);
            assertThat(send(port, "/api/checkout/" + recover.id() + "/qr", freshToken, null).statusCode()).isEqualTo(401);
        } finally { gateway.close(); context.close(); client.close(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Controlled {
        @Bean @Primary ObservationService controlled(SnapshotStore store, BridgeAccess access) { return new ObservationService(store, CLOCK, access.deviceId); }
        @Bean @Primary BridgeAccess controlledAccess(org.springframework.core.env.Environment env) { return new BridgeAccess(env, new PayerTokens(ACCESS_CLOCK)); }
    }
    static class MutableClock extends Clock {
        volatile Instant now;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
