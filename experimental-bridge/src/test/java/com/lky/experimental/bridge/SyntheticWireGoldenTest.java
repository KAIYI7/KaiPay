package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
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

/**
 * Exact response and snapshot bytes outside observed mode, written as literal templates. This class uses only APIs
 * that already existed at the pre-observed-mode baseline, so the same file can be run unchanged against that commit to
 * show that synthetic integration and non-integration responses are byte-identical before and after.
 */
class SyntheticWireGoldenTest {
    static final String START = "2026-01-01T00:00:00Z";
    @TempDir Path directory;
    Path file;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    MockMvc mvc;
    CheckoutHttpTest.MutableClock clock;

    @BeforeEach void setup() {
        var builder = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new BridgeConfiguration().strictJson().customize(builder);
        mapper = builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        file = directory.resolve("state.json");
        clock = new CheckoutHttpTest.MutableClock(Instant.parse(START));
        store = new SnapshotStore(file, mapper);
    }
    void configure(MockEnvironment environment) {
        BridgeAccess access = new BridgeAccess(environment);
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
    MockHttpServletResponse json(MockHttpServletRequestBuilder request, String body, String token) throws Exception {
        return send(request.contentType(MediaType.APPLICATION_JSON).content(body), token);
    }
    static void exact(MockHttpServletResponse response, int status, String body) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(body);
    }
    static String integration(String reference, String id, String status, String evidence, String review, int revision) {
        return "{\"reference\":\"" + reference + "\",\"checkoutId\":\"" + id + "\",\"amountMinor\":863,\"status\":\"" + status
                + "\",\"evidence\":\"" + evidence + "\",\"createdAt\":\"" + START + "\",\"expiresAt\":\"2026-01-01T00:10:00Z\",\"reviewExpiresAt\":"
                + review + ",\"revision\":" + revision + "}";
    }
    static String request(String id, int amount, String status, String observation, String grant, int revision, String reference) {
        return "{\"id\":\"" + id + "\",\"amountMinor\":" + amount + ",\"createdAt\":\"" + START + "\",\"expiresAt\":\"2026-01-01T00:10:00Z\",\"status\":\""
                + status + "\",\"observationId\":" + observation + ",\"lifecycleVersion\":2,\"paymentClosed\":false,\"reviewGrant\":" + grant
                + ",\"revision\":" + revision + ",\"integrationReference\":" + reference + "}";
    }

    @Test void syntheticIntegrationResponsesAndSnapshotKeepTheirExactBytes() throws Exception {
        configure(IntegrationHttpTest.environment());
        String reference = UUID.randomUUID().toString(), route = IntegrationHttpTest.ROUTE + reference;
        MockHttpServletResponse created = json(put(route), IntegrationHttpTest.body(863), IntegrationHttpTest.INTEGRATION);
        String id = mapper.readTree(created.getContentAsString()).get("checkoutId").asText();
        exact(created, 201, integration(reference, id, "AWAITING_PAYMENT", "NONE", "null", 1));
        exact(json(put(route), IntegrationHttpTest.body(863), IntegrationHttpTest.INTEGRATION), 200, integration(reference, id, "AWAITING_PAYMENT", "NONE", "null", 1));
        exact(send(get(route), IntegrationHttpTest.INTEGRATION), 200, integration(reference, id, "AWAITING_PAYMENT", "NONE", "null", 1));
        exact(json(put(route), IntegrationHttpTest.body(864), IntegrationHttpTest.INTEGRATION), 409, "{\"error\":\"REFERENCE_CONFLICT\"}");
        exact(json(put(IntegrationHttpTest.ROUTE + UUID.randomUUID()), IntegrationHttpTest.body(863), IntegrationHttpTest.INTEGRATION), 409, "{\"error\":\"SAME_AMOUNT_HELD\"}");
        exact(send(get(IntegrationHttpTest.ROUTE + UUID.randomUUID()), IntegrationHttpTest.INTEGRATION), 404, "{\"error\":\"REFERENCE_NOT_FOUND\"}");
        assertThat(Files.readString(file)).isEqualTo("{\"requests\":[" + request(id, 863, "AWAITING_PAYMENT", "null", "null", 1, "\"" + reference + "\"")
                + "],\"observations\":[]}");

        clock.now = Instant.parse("2026-01-01T00:00:20Z");
        String event = UUID.randomUUID().toString(), notification = UUID.randomUUID().toString();
        String candidate = "{\"eventId\":\"" + event + "\",\"notificationId\":\"" + notification + "\",\"sequence\":1,\"observedAt\":\"2026-01-01T00:00:20Z\","
                + "\"amountMinor\":863,\"source\":\"SYNTHETIC\",\"deviceId\":\"" + NotificationCandidateTest.DEVICE_ID + "\",\"classification\":\"INCOMING_CANDIDATE\","
                + "\"classifierProfile\":\"synthetic.debug.v1\",\"evidenceQuality\":\"SYNTHETIC\"}";
        String observation = "{\"candidate\":" + candidate + ",\"disposition\":\"MATCHED\",\"requestId\":\"" + id + "\",\"acceptedAt\":\"2026-01-01T00:00:20Z\"}";
        exact(json(post("/api/notification-candidates"), candidate, NotificationHttpTest.LIVE), 200, observation);
        String grant = "{\"eventId\":\"" + event + "\",\"reviewExpiresAt\":\"2026-01-01T00:40:00Z\",\"revoked\":false}";
        exact(send(get(route), IntegrationHttpTest.INTEGRATION), 200, integration(reference, id, "PAYMENT_DETECTED", "SYNTHETIC", "\"2026-01-01T00:40:00Z\"", 2));
        String detected = request(id, 863, "PAYMENT_DETECTED", "\"" + event + "\"", grant, 2, "\"" + reference + "\"");
        exact(send(get("/api/state"), BridgeHttpTest.RECEIVER), 200, "{\"requests\":[" + detected + "],\"observations\":[" + observation + "]}");
        assertThat(Files.readString(file)).isEqualTo("{\"requests\":[" + detected + "],\"observations\":[" + observation + "]}");
        String attestation = "{\"historyChecked\":true,\"expectedEventId\":\"" + event + "\"}";
        exact(json(post("/api/requests/" + id + "/verify"), attestation, BridgeHttpTest.RECEIVER), 200,
                request(id, 863, "AWAITING_VERIFICATION", "\"" + event + "\"", grant, 3, "\"" + reference + "\""));
        exact(json(post("/api/requests/" + id + "/confirm"), attestation, BridgeHttpTest.RECEIVER), 200,
                request(id, 863, "MANUALLY_CONFIRMED", "\"" + event + "\"", grant, 4, "\"" + reference + "\""));
        exact(send(get(route), IntegrationHttpTest.INTEGRATION), 200, integration(reference, id, "MANUALLY_CONFIRMED", "SYNTHETIC", "\"2026-01-01T00:40:00Z\"", 4));
        // A genuine candidate is still refused, framed exactly as before.
        String genuine = candidate.replace(event, UUID.randomUUID().toString()).replace(notification, UUID.randomUUID().toString())
                .replace("\"SYNTHETIC\",\"deviceId\"", "\"TNG_NOTIFICATION_CANDIDATE\",\"deviceId\"")
                .replace("synthetic.debug.v1", "tng.receipt.en.observed.v1").replace("\"evidenceQuality\":\"SYNTHETIC\"", "\"evidenceQuality\":\"RULE_MATCHED\"");
        exact(json(post("/api/notification-candidates"), genuine, NotificationHttpTest.LIVE), 422, "{\"error\":\"GENUINE_DISABLED\"}");
    }

    @Test void nonIntegrationReceiverCreateAndStateKeepTheirExactBytes() throws Exception {
        configure(NotificationHttpTest.environment());
        MockHttpServletResponse created = json(post("/api/requests"), "{\"amountMinor\":1234}", BridgeHttpTest.RECEIVER);
        String id = mapper.readTree(created.getContentAsString()).get("id").asText();
        String body = request(id, 1234, "AWAITING_PAYMENT", "null", "null", 1, "null");
        exact(created, 200, body);
        exact(send(get("/api/state"), BridgeHttpTest.RECEIVER), 200, "{\"requests\":[" + body + "],\"observations\":[]}");
        assertThat(Files.readString(file)).isEqualTo("{\"requests\":[" + body + "],\"observations\":[]}");
        exact(send(get(IntegrationHttpTest.ROUTE + UUID.randomUUID()), IntegrationHttpTest.INTEGRATION), 404, "{\"error\":\"INTEGRATION_DISABLED\"}");
    }

    @Test void receiverAndPayerPagesKeepTheirExactBytesOutsideObservedMode() throws Exception {
        byte[] index = Files.readAllBytes(Path.of("src/main/resources/demo/index.html"));
        byte[] checkout = Files.readAllBytes(Path.of("src/main/resources/demo/checkout.html"));
        configure(IntegrationHttpTest.environment());
        assertThat(send(get("/"), null).getContentAsByteArray()).isEqualTo(index);
        assertThat(send(get("/checkout"), null).getContentAsByteArray()).isEqualTo(CheckoutController.syntheticCheckout(checkout));
        configure(NotificationHttpTest.environment());
        assertThat(send(get("/"), null).getContentAsByteArray()).isEqualTo(index);
        assertThat(send(get("/checkout"), null).getContentAsByteArray()).isEqualTo(checkout);
    }
}
