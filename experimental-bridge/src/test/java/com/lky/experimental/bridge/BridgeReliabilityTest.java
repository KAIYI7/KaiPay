package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** SYNTHETIC-only HTTP/persistence checks; no Android OS or payment-provider evidence. */
class BridgeReliabilityTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final TestClock CLOCK = new TestClock();
    private static volatile CountDownLatch persisted = new CountDownLatch(1);
    private static volatile CountDownLatch releaseAcknowledgement = new CountDownLatch(1);
    @TempDir Path directory;

    ServletWebServerApplicationContext start(int port) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(
                BridgeApplication.class, ReliabilityConfiguration.class).run(
                "--server.address=127.0.0.1", "--server.port=" + port,
                "--server.forward-headers-strategy=NONE", "--bridge.enabled=true",
                "--bridge.receiver-token=" + BridgeHttpTest.RECEIVER,
                "--bridge.device-token=" + BridgeHttpTest.DEVICE,
                "--bridge.live-device-token=" + NotificationHttpTest.LIVE,
                "--bridge.live-device-id=" + NotificationCandidateTest.DEVICE_ID,
                "--bridge.store=" + directory.resolve("state.json"), "--bridge.qr-directory=");
    }

    HttpRequest request(int port, String path, String role, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + role);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return request.build();
    }

    Candidate synthetic(int amount, Instant observedAt) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, observedAt, amount, Source.SYNTHETIC,
                NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC");
    }

    @Test void lostAcknowledgementThenReceiverRestartReplaysExactlyOneDurableSyntheticEvent() throws Exception {
        CLOCK.now = START;
        persisted = new CountDownLatch(1); releaseAcknowledgement = new CountDownLatch(1);
        Candidate event = synthetic(6123, START.plusSeconds(599));
        String body;
        ObjectMapper mapper;
        PaymentRequest created;
        Observation original;
        int port;
        try (HttpClient client = HttpClient.newHttpClient()) {
            try (var server = start(0)) {
                mapper = server.getBean(ObjectMapper.class); port = server.getWebServer().getPort();
                var response = client.send(request(port, "/api/requests", BridgeHttpTest.RECEIVER,
                        "{\"amountMinor\":6123}"), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                created = mapper.readValue(response.body(), PaymentRequest.class);
                CLOCK.now = event.observedAt(); body = mapper.writeValueAsString(event);
                HttpRequest withheld = HttpRequest.newBuilder(request(port, "/api/notification-candidates",
                        NotificationHttpTest.LIVE, body), (name, value) -> true)
                        .timeout(Duration.ofSeconds(1)).header("X-Synthetic-Withhold-Ack", "true").build();
                var pending = client.sendAsync(withheld, HttpResponse.BodyHandlers.ofString());
                try {
                    assertThat(persisted.await(5, TimeUnit.SECONDS)).isTrue();
                    Snapshot disk = mapper.readValue(directory.resolve("state.json").toFile(), Snapshot.class);
                    assertThat(disk.observations()).hasSize(1);
                    original = disk.observations().getFirst();
                    assertThat(original.candidate()).isEqualTo(event);
                    assertThat(original.acceptedAt()).isEqualTo(event.observedAt());
                    assertThat(original.disposition()).isEqualTo(Disposition.MATCHED);
                    assertThat(disk.requests().getFirst().status()).isEqualTo(Status.PAYMENT_DETECTED);
                    assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(HttpTimeoutException.class);
                } finally { releaseAcknowledgement.countDown(); }
            }
            // This is an actual closed listener, not an injected server error response.
            assertThatThrownBy(() -> client.send(request(port, "/api/notification-candidates",
                    NotificationHttpTest.LIVE, body), HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(IOException.class);
            CLOCK.now = created.expiresAt().plusSeconds(1);
            try (var restarted = start(port)) {
                var replay = client.send(request(port, "/api/notification-candidates", NotificationHttpTest.LIVE, body),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(replay.statusCode()).isEqualTo(200);
                assertThat(mapper.readValue(replay.body(), Observation.class)).isEqualTo(original);
                assertThat(mapper.readTree(replay.body()).path("candidate").path("eventId").asText())
                        .isEqualTo(event.eventId().toString());
                Candidate changed = new Candidate(event.eventId(), event.notificationId(), event.sequence(), event.observedAt(),
                        6124, event.source(), event.deviceId(), event.classification(), event.classifierProfile(), event.evidenceQuality());
                var conflict = client.send(request(port, "/api/notification-candidates", NotificationHttpTest.LIVE,
                        mapper.writeValueAsString(changed)), HttpResponse.BodyHandlers.ofString());
                assertThat(conflict.statusCode()).isEqualTo(409);
                assertThat(conflict.body()).isEqualTo("{\"error\":\"EVENT_ID_REUSED\"}");
                Snapshot disk = mapper.readValue(directory.resolve("state.json").toFile(), Snapshot.class);
                assertThat(disk.observations()).containsExactly(original);
                PaymentRequest retained = disk.requests().getFirst();
                assertThat(retained.status()).isEqualTo(Status.PAYMENT_DETECTED);
                assertThat(retained.reviewGrant().eventId()).isEqualTo(event.eventId());
                assertThat(retained.reviewGrant().reviewExpiresAt()).isEqualTo(created.expiresAt().plusSeconds(1800));
                assertThat(retained.reviewGrant().revoked()).isFalse();
                assertThat(client.send(request(port, "/api/requests/" + created.id() + "/confirm",
                        NotificationHttpTest.LIVE, mapper.writeValueAsString(new Confirmation(true, event.eventId()))),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            }
        }
    }

    @Test void firstAdmissionAtDeadlineAndStaleDeliveryCannotGainReviewAcrossReceiverRestart() throws Exception {
        CLOCK.now = START;
        try (HttpClient client = HttpClient.newHttpClient()) {
            ObjectMapper mapper;
            Candidate late;
            Candidate stale;
            PaymentRequest created;
            int port;
            try (var server = start(0)) {
                mapper = server.getBean(ObjectMapper.class); port = server.getWebServer().getPort();
                var response = client.send(request(port, "/api/requests", BridgeHttpTest.RECEIVER,
                        "{\"amountMinor\":7123}"), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                created = mapper.readValue(response.body(), PaymentRequest.class);
                late = synthetic(7123, created.expiresAt().minusSeconds(1));
                stale = synthetic(7124, START);
            }
            CLOCK.now = created.expiresAt();
            try (var restarted = start(port)) {
                var response = client.send(request(port, "/api/notification-candidates", NotificationHttpTest.LIVE,
                        mapper.writeValueAsString(late)), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                Observation acknowledgement = mapper.readValue(response.body(), Observation.class);
                assertThat(acknowledgement.candidate()).isEqualTo(late);
                assertThat(acknowledgement.disposition()).isEqualTo(Disposition.EXPIRED);
                assertThat(acknowledgement.requestId()).isNull();
                assertThat(acknowledgement.acceptedAt()).isEqualTo(created.expiresAt());
                var staleResponse = client.send(request(port, "/api/notification-candidates", NotificationHttpTest.LIVE,
                        mapper.writeValueAsString(stale)), HttpResponse.BodyHandlers.ofString());
                assertThat(staleResponse.statusCode()).isEqualTo(200);
                assertThat(mapper.readValue(staleResponse.body(), Observation.class).disposition()).isEqualTo(Disposition.DELAYED);
                for (String step : new String[]{"verify", "confirm"}) {
                    var blocked = client.send(request(port, "/api/requests/" + created.id() + "/" + step,
                            BridgeHttpTest.RECEIVER, mapper.writeValueAsString(new Confirmation(true, late.eventId()))),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(blocked.statusCode()).isEqualTo(409);
                    assertThat(blocked.body()).isEqualTo("{\"error\":\"VERIFICATION_NOT_ELIGIBLE\"}");
                }
                Snapshot disk = mapper.readValue(directory.resolve("state.json").toFile(), Snapshot.class);
                assertThat(disk.observations()).hasSize(2);
                assertThat(disk.requests().getFirst().status()).isEqualTo(Status.EXPIRED);
                assertThat(disk.requests().getFirst().reviewGrant()).isNull();
                assertThat(disk.requests().getFirst().observationId()).isNull();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReliabilityConfiguration {
        @Bean @Primary ObservationService reliabilityObservations(SnapshotStore store, BridgeAccess access, Environment environment) {
            return new ObservationService(store, CLOCK, access.deviceId,
                    environment.getProperty("bridge.review-period-seconds", Integer.class, 1800));
        }
        @Bean FilterRegistrationBean<Filter> withheldSyntheticAcknowledgement() {
            FilterRegistrationBean<Filter> filter = new FilterRegistrationBean<>((request, response, chain) -> {
                if (!"true".equals(((jakarta.servlet.http.HttpServletRequest) request).getHeader("X-Synthetic-Withhold-Ack"))) {
                    chain.doFilter(request, response); return;
                }
                var buffered = new ContentCachingResponseWrapper((jakarta.servlet.http.HttpServletResponse) response);
                chain.doFilter(request, buffered);
                persisted.countDown();
                try {
                    if (!releaseAcknowledgement.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic acknowledgement gate timed out");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
                buffered.copyBodyToResponse();
            });
            filter.addUrlPatterns("/api/notification-candidates");
            return filter;
        }
    }
    private static final class TestClock extends Clock {
        volatile Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
