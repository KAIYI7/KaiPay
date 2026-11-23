package com.lky.experimental.bridge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PayerRevocationHttpTest {
    @TempDir Path directory;
    private final CheckoutHttpTest fixture = new CheckoutHttpTest();
    private final PayerTokensTest.MutableClock clock = new PayerTokensTest.MutableClock();

    @BeforeEach void setup() {
        fixture.directory = directory;
        fixture.setup();
        fixture.access = new BridgeAccess(NotificationHttpTest.environment(), new PayerTokens(clock, () -> 0));
        fixture.mvc = MockMvcBuilders.standaloneSetup(new BridgeController(fixture.service),
                        new CheckoutController(fixture.service, fixture.access, new ReceivingQr("")))
                .setControllerAdvice(new BridgeErrors())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(fixture.mapper),
                        new org.springframework.http.converter.ByteArrayHttpMessageConverter())
                .addFilters(new BridgeFilter(fixture.access)).build();
    }

    @AfterEach void close() throws Exception { fixture.close(); }

    @Test void receiverRevokeIsImmediateIdempotentAndPreservesPaymentSnapshot() throws Exception {
        var one = fixture.create(1234); var two = fixture.create(5678);
        var first = fixture.link(one.id()); var unrelated = fixture.link(two.id());
        var before = fixture.service.view();
        for (int i = 0; i < 2; i++) fixture.mvc.perform(post(revoke(one.id()))
                .header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNoContent());
        denied(one.id(), first.token());
        fixture.read(two.id(), unrelated.token(), Model.Status.AWAITING_PAYMENT, Model.PayerEvidence.NONE);
        var replacement = fixture.link(one.id());
        fixture.read(one.id(), replacement.token(), Model.Status.AWAITING_PAYMENT, Model.PayerEvidence.NONE);
        denied(one.id(), first.token());
        assertThat(fixture.service.view()).isEqualTo(before);
    }

    @Test void payerSimulationAndLiveRolesCannotRevokeOrMint() throws Exception {
        var request = fixture.create(1234); var link = fixture.link(request.id());
        for (String role : new String[]{link.token(), BridgeHttpTest.DEVICE, NotificationHttpTest.LIVE})
            for (String operation : new String[]{"payer-access", "payer-revoke"})
                fixture.mvc.perform(post("/api/requests/" + request.id() + "/" + operation)
                        .header("Authorization", "Bearer " + role).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isUnauthorized());
        fixture.read(request.id(), link.token(), Model.Status.AWAITING_PAYMENT, Model.PayerEvidence.NONE);
        fixture.mvc.perform(post(revoke(UUID.randomUUID())).header("Authorization", "Bearer " + BridgeHttpTest.RECEIVER)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    }

    @Test void expiredCapabilityReturnsUnauthorizedAtBoundaryAndAfterRollback() throws Exception {
        var request = fixture.create(1234); var link = fixture.link(request.id());
        clock.now = clock.now.plus(PayerTokens.LIFETIME).minusNanos(1);
        fixture.read(request.id(), link.token(), Model.Status.AWAITING_PAYMENT, Model.PayerEvidence.NONE);
        clock.now = clock.now.plusNanos(1); denied(request.id(), link.token());
        clock.now = clock.now.minus(PayerTokens.LIFETIME); denied(request.id(), link.token());
    }

    @Test void backendRequiresLoopbackEvenWithTlsEnabled() {
        for (String address : new String[]{"0.0.0.0", "192.0.2.1", "::"})
            assertThatThrownBy(() -> new BridgeAccess(BridgeHttpTest.environment().withProperty("server.address", address)
                    .withProperty("server.ssl.enabled", "true")))
                    .hasMessage("Experimental backend must bind to loopback");
        for (String address : new String[]{"127.0.0.1", "::1"})
            assertThatCode(() -> new BridgeAccess(BridgeHttpTest.environment().withProperty("server.address", address)
                    .withProperty("server.ssl.enabled", "true"))).doesNotThrowAnyException();
    }

    private String revoke(UUID id) { return "/api/requests/" + id + "/payer-revoke"; }
    private void denied(UUID id, String token) throws Exception {
        for (String suffix : new String[]{"", "/qr"})
            fixture.mvc.perform(get("/api/checkout/" + id + suffix).header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(content().json("{\"error\":\"UNAUTHORIZED\"}"));
    }
}
