package com.lky.experimental.bridge;

import static com.lky.experimental.bridge.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Receiver reachability only; this probe never reads or changes financial evidence. */
class OperationalHealthTest {
    @TempDir Path directory;
    ObjectMapper mapper;
    SnapshotStore store;
    ObservationService service;
    LifecycleTest.TestClock clock;
    BridgeAccess access;
    MockMvc mvc;

    @BeforeEach void setup() {
        mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        clock = new LifecycleTest.TestClock();
        store = new SnapshotStore(directory.resolve("state.json"), mapper);
        service = new ObservationService(store, clock, NotificationCandidateTest.DEVICE_ID);
        access = new BridgeAccess(NotificationHttpTest.environment());
        mvc = MockMvcBuilders.standaloneSetup(new BridgeController(service))
                .setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new BridgeFilter(access)).build();
    }
    @AfterEach void close() throws Exception { store.close(); }

    @Test void receiverGetsOnlyCurrentProcessSampleAndUnknownPhoneDimensions() throws Exception {
        Instant before = Instant.now();
        var response = mvc.perform(get("/api/health").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andReturn().getResponse();
        var body = mapper.readTree(response.getContentAsString());
        var fields = new ArrayList<String>(); body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("sampledAt", "backendProcess", "androidListener",
                "androidQueue", "androidWorker", "notificationTransport");
        assertThat(Instant.parse(body.path("sampledAt").asText())).isBetween(before, Instant.now());
        assertThat(body.path("backendProcess").asText()).isEqualTo("RUNNING");
        for (String name : new String[]{"androidListener", "androidQueue", "androidWorker", "notificationTransport"})
            assertThat(body.path(name).asText()).as(name).isEqualTo("UNKNOWN");
        assertThat(response.getContentAsString()).doesNotContain(BridgeHttpTest.RECEIVER, BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE);
        assertThat(Files.exists(directory.resolve("state.json"))).isFalse();
    }

    @Test void allOtherRolesAndDuplicateCredentialsAreDenied() throws Exception {
        for (String token : new String[]{"", "wrong", BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE, access.payers.issue(UUID.randomUUID())})
            mvc.perform(get("/api/health").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER,
                "Bearer " + BridgeHttpTest.RECEIVER)).andExpect(status().isUnauthorized());
    }

    @Test void noncanonicalPathsQueryAndOtherMethodsAreRejected() throws Exception {
        for (String path : new String[]{"/api/health/", "/api/health;extra=1", "/api/%68ealth", "/api//health", "/api/health?detail=true"})
            mvc.perform(get(path).with(request -> {
                // MockMvc's URI builder normalizes duplicate slashes; exercise the raw filter boundary.
                request.setRequestURI(path.split("\\?", 2)[0]); return request;
            }).header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).as(path).isEqualTo(404));
        for (String method : new String[]{"POST", "PUT", "DELETE", "HEAD", "OPTIONS"})
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/health")
                    .header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER))
                    .andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/health").with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })
                .header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER).header("X-Forwarded-Proto", "https"))
                .andExpect(status().isForbidden());
    }

    @Test void healthDoesNotReadExpireOrRewriteFinancialState() throws Exception {
        PaymentRequest request = service.create(new CreateRequest(1234));
        byte[] persisted = Files.readAllBytes(directory.resolve("state.json"));
        clock.now = request.expiresAt().plusSeconds(1);
        mvc.perform(get("/api/health").header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER))
                .andExpect(status().isOk());
        assertThat(Files.readAllBytes(directory.resolve("state.json"))).isEqualTo(persisted);
        assertThat(mapper.readValue(persisted, Snapshot.class).requests().getFirst().status()).isEqualTo(Status.AWAITING_PAYMENT);
        // A real financial read still expires it, proving the test crosses the deadline.
        assertThat(service.view().requests().getFirst().status()).isEqualTo(Status.EXPIRED);
    }
}
