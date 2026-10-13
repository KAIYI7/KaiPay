package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.lky.experimental.bridge.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(OutputCaptureExtension.class)
class BridgeHttpTest {
    static final String RECEIVER = "R".repeat(40), DEVICE = "D".repeat(40);
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
                .featuresToDisable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();
        store = new SnapshotStore(directory.resolve("state.json"), mapper);
        service = new ObservationService(store, Clock.systemUTC());
        mvc = MockMvcBuilders.standaloneSetup(new BridgeController(service)).setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper), new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(new BridgeAccess(environment()))).build();
    }
    @AfterEach void close() throws Exception { store.close(); }
    static MockEnvironment environment() {
        return new MockEnvironment().withProperty("bridge.enabled", "true").withProperty("server.address", "127.0.0.1")
                .withProperty("server.forward-headers-strategy", "NONE").withProperty("bridge.device-token", DEVICE)
                .withProperty("bridge.receiver-token", RECEIVER);
    }
    Candidate candidate() { return new Candidate(UUID.randomUUID(), UUID.randomUUID(), 1, Instant.now(), 1234, Source.SIMULATED); }

    @Test void noCredentialAndCrossRoleCredentialsCannotReadCreateOrConfirm() throws Exception {
        for (String token : new String[]{"", DEVICE, "wrong-token"}) {
            mvc.perform(get("/api/state").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/requests").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":1234}")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/requests/" + UUID.randomUUID() + "/confirm").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":true}")).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/observations").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(candidate()))).andExpect(status().isUnauthorized());
        assertThat(service.view().requests()).isEmpty(); assertThat(service.view().observations()).isEmpty();
    }

    @Test void successfulHttpEndToEndRequiresReceiverVerificationAndConfirmation() throws Exception {
        String created = mvc.perform(post("/api/requests").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":1234}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_PAYMENT")).andReturn().getResponse().getContentAsString();
        UUID id = mapper.readValue(created, PaymentRequest.class).id();
        String event = mapper.writeValueAsString(candidate());
        mvc.perform(post("/api/observations").header("Authorization", "Bearer " + DEVICE).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isOk()).andExpect(jsonPath("$.disposition").value("MATCHED"));
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + RECEIVER)).andExpect(status().isOk()).andExpect(jsonPath("$.requests[0].status").value("PAYMENT_DETECTED"));
        mvc.perform(post("/api/requests/" + id + "/confirm").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":true}")).andExpect(status().isConflict());
        mvc.perform(post("/api/requests/" + id + "/verify").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_VERIFICATION"));
        mvc.perform(post("/api/requests/" + id + "/confirm").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"historyChecked\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MANUALLY_CONFIRMED"));
        mvc.perform(post("/api/observations").header("Authorization", "Bearer " + DEVICE).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isOk());
        assertThat(service.view().observations()).hasSize(1);
    }

    @Test void rawTextSecretsUnknownFieldsMalformedAndDuplicateJsonNeverAppearInErrorsOrLogs(CapturedOutput logs) throws Exception {
        String marker = "SENSITIVE_SYNTHETIC_MARKER_DO_NOT_LOG";
        for (String payload : new String[]{"{\"amountMinor\":1234,\"rawText\":\"" + marker + "\"}",
                "{\"amountMinor\":\"" + marker + "\"}", "{\"amountMinor\":1234,\"amountMinor\":1}",
                "{\"amountMinor\":1234} {\"amountMinor\":1}", "{"}) {
            String response = mvc.perform(post("/api/requests").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).isEqualTo("{\"error\":\"INVALID_REQUEST\"}");
        }
        assertThat(service.view().requests()).isEmpty();
        assertThat(logs.getAll()).doesNotContain(marker, RECEIVER, DEVICE);
        assertThat(Files.exists(directory.resolve("state.json"))).isFalse();
    }

    @Test void decimalStringOverflowAndFractionalMinorUnitsAreRejected() throws Exception {
        for (String amount : new String[]{"12.34", "\"1234\"", "2147483648", "1.0"}) {
            mvc.perform(post("/api/requests").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":" + amount + "}")).andExpect(status().isBadRequest());
        }
        assertThat(service.view().requests()).isEmpty();
    }

    @Test void liveOrNumericSourcesCannotMasqueradeAsSimulation() throws Exception {
        String good = mapper.writeValueAsString(candidate());
        for (String source : new String[]{"0", "\"LIVE\"", "null", "\"SIMULATED \""}) {
            var result = mvc.perform(post("/api/observations").header("Authorization", "Bearer " + DEVICE).contentType(MediaType.APPLICATION_JSON).content(good.replace("\"SIMULATED\"", source))).andReturn();
            assertThat(result.getResponse().getStatus()).isIn(400, 422);
        }
        assertThat(service.view().observations()).isEmpty();
    }

    @Test void oversizedBodiesAndNonJsonAreRejectedBeforeParsing() throws Exception {
        mvc.perform(post("/api/requests").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content("X".repeat(4097))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/requests").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.TEXT_PLAIN).content("{\"amountMinor\":1234}")).andExpect(status().isUnsupportedMediaType());
        assertThat(service.view().requests()).isEmpty();
    }

    @Test void remotePlainHttpAndForgedForwardedTlsAreRejected() throws Exception {
        mvc.perform(get("/api/state").with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })
                .header("Authorization", "Bearer " + RECEIVER).header("X-Forwarded-Proto", "https")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("TLS_REQUIRED"));
        mvc.perform(get("/api/state").with(request -> { request.setLocalAddr("0.0.0.0"); return request; })
                .header("Authorization", "Bearer " + RECEIVER)).andExpect(status().isForbidden());
    }

    @Test void duplicateAuthorizationAndEncodedPathsCannotBypassRoles() throws Exception {
        mvc.perform(get("/api/state").header("Authorization", "Bearer " + RECEIVER, "Bearer " + RECEIVER)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/observations;role=receiver").header("Authorization", "Bearer " + RECEIVER).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(candidate()))).andExpect(status().isNotFound());
    }

    @Test void publicDemoUsesPlaceholderAndSecurityHeaders() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Placeholder QR")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UNVERIFIED")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));
        mvc.perform(get("/demo.js")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("setInterval")));
    }

    @Test void missingShortEqualCredentialsAndUnsafeBindFailClosed() {
        for (String token : new String[]{"", "short", "with spaces".repeat(4)}) {
            assertThatThrownBy(() -> new BridgeAccess(environment().withProperty("bridge.device-token", token))).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new BridgeAccess(environment().withProperty("bridge.receiver-token", DEVICE))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BridgeAccess(environment().withProperty("server.address", "0.0.0.0"))).hasMessage("TLS required outside loopback");
        assertThatThrownBy(() -> new BridgeAccess(environment().withProperty("server.forward-headers-strategy", "NATIVE"))).hasMessage("Forwarded headers must remain disabled");
    }
}
