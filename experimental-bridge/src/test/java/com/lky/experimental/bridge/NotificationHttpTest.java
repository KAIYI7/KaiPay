package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationHttpTest {
    static final String LIVE = "L".repeat(40);
    @TempDir Path directory;
    SnapshotStore store;
    ObservationService service;
    ObjectMapper mapper;
    MockMvc mvc;
    @BeforeEach void setup() {
        var builder = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
        new BridgeConfiguration().strictJson().customize(builder);
        mapper = builder.featuresToEnable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                        com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        store = new SnapshotStore(directory.resolve("state.json"), mapper);
        service = new ObservationService(store, Clock.systemUTC(), NotificationCandidateTest.DEVICE_ID);
        mvc = mvc(environment());
    }
    MockMvc mvc(MockEnvironment environment) {
        return MockMvcBuilders.standaloneSetup(new BridgeController(service)).setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper), new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(new BridgeAccess(environment))).build();
    }
    static MockEnvironment environment() { return BridgeHttpTest.environment().withProperty("bridge.live-device-token", LIVE)
            .withProperty("bridge.live-device-id", NotificationCandidateTest.DEVICE_ID.toString()); }
    @AfterEach void close() throws Exception { store.close(); }
    Candidate candidate() { return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), 1234, Source.SYNTHETIC,
            NotificationCandidateTest.DEVICE_ID, "INCOMING_CANDIDATE", "synthetic.debug.v1", "SYNTHETIC"); }

    @Test void liveRoleCannotReadCreateConfirmOrSimulateAndOtherRolesCannotUploadOrInvalidate() throws Exception {
        String event = mapper.writeValueAsString(candidate());
        for (String credential : new String[]{"", "incorrect", BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE}) {
            mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + credential).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/notification-candidates/" + UUID.randomUUID() + "/invalidate").header("Authorization", "Bearer " + credential).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOTIFICATION_CHANGED\"}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + LIVE)).andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/requests", "/api/observations", "/api/requests/" + UUID.randomUUID() + "/confirm", "/api/observations/" + UUID.randomUUID() + "/resolve"})
            mvc.perform(post(path).header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isUnauthorized());
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void missingLiveConfigurationDisablesRouteAndPartialOrReusedCredentialsFailStartup() throws Exception {
        mvc(BridgeHttpTest.environment()).perform(post("/api/notification-candidates").header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(candidate()))).andExpect(status().isNotFound());
        for (MockEnvironment env : new MockEnvironment[]{BridgeHttpTest.environment().withProperty("bridge.live-device-token", LIVE),
                environment().withProperty("bridge.live-device-token", BridgeHttpTest.RECEIVER), environment().withProperty("bridge.live-device-token", BridgeHttpTest.DEVICE),
                environment().withProperty("bridge.live-device-id", "invalid")})
            assertThatThrownBy(() -> new BridgeAccess(env)).isInstanceOf(IllegalStateException.class);
    }

    @Test void strictSchemaAndSupportedEvidenceTuplesRejectRawDataAndMasquerading() throws Exception {
        String event = mapper.writeValueAsString(candidate());
        for (String bad : new String[]{event.replace("\"SYNTHETIC\"", "\"TNG_NOTIFICATION_CANDIDATE\""),
                event.replace("\"INCOMING_CANDIDATE\"", "\"AMBIGUOUS\""), event.replace("1234", "\"1234\""),
                event.substring(0, event.length() - 1) + ",\"body\":\"private notification\"}",
                event.replaceFirst("\"observedAt\":\"[^\"]+\"", "\"observedAt\":1767225600"),
                event.replaceFirst("\"observedAt\":\"[^\"]+\"", "\"observedAt\":\"not-an-instant\""),
                event.substring(0, event.length() - 1) + ",\"amountMinor\":1}"}) {
            var result = mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content(bad)).andReturn().getResponse();
            assertThat(result.getStatus()).isIn(400, 422);
            assertThat(result.getContentAsString()).doesNotContain("private notification", LIVE);
        }
        mvc.perform(post("/api/notification-candidates;role=receiver").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isNotFound());
        mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + LIVE, "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/notification-candidates").with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })
                .header("X-Forwarded-Proto", "https").header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("TLS_REQUIRED"));
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void androidSyntheticPipelinePersistsDuplicateAcknowledgesAndReceiverSeparatelyConfirms() throws Exception {
        UUID request = service.create(new CreateRequest(1234)).id();
        Candidate input = candidate(); String event = mapper.writeValueAsString(input);
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/notification-candidates").header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("MATCHED")).andExpect(jsonPath("$.candidate.source").value("SYNTHETIC"));
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)).andExpect(status().isOk()).andExpect(jsonPath("$.requests[0].status").value("PAYMENT_DETECTED"));
        mvc.perform(post("/api/requests/" + request + "/confirm").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":true}")).andExpect(status().isConflict());
        for (String step : new String[]{"verify", "confirm"}) mvc.perform(post("/api/requests/" + request + "/" + step).header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":true}")).andExpect(status().isOk());
        assertThat(service.view().observations()).hasSize(1);
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.MANUALLY_CONFIRMED);
    }

    @Test void invalidationAndManualResolutionHaveIndependentRolesAndGuardConditions() throws Exception {
        UUID one = service.create(new CreateRequest(1234)).id(); service.create(new CreateRequest(1234));
        Candidate event = candidate(); service.ingestNotification(event);
        mvc.perform(post("/api/observations/" + event.eventId() + "/resolve").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + one + "\",\"historyChecked\":false}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/observations/" + event.eventId() + "/resolve").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + one + "\",\"historyChecked\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("MATCHED"));
        mvc.perform(post("/api/notification-candidates/" + UUID.randomUUID() + "/invalidate").header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOTIFICATION_CHANGED\"}")).andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/notification-candidates/" + event.eventId() + "/invalidate").header("Authorization", "Bearer " + LIVE).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOTIFICATION_CHANGED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("INVALIDATED"));
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.AMBIGUOUS);
    }
}
