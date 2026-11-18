package com.lky.experimental.bridge;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class BridgeConfiguration {
    @Bean Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder -> builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .featuresToDisable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    @Bean BridgeAccess access(Environment env) { return new BridgeAccess(env); }
    @Bean @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
    ReceivingQr receivingQr(Environment env) { return new ReceivingQr(env.getProperty("bridge.qr-directory", "")); }
    @Bean FilterRegistrationBean<BridgeFilter> bridgeFilter(BridgeAccess access, BridgeLog log) {
        FilterRegistrationBean<BridgeFilter> registration = new FilterRegistrationBean<>(new BridgeFilter(access, log));
        registration.setOrder(Integer.MIN_VALUE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean(destroyMethod = "close") @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
    SnapshotStore store(Environment env, ObjectMapper mapper, BridgeAccess access) {
        return new SnapshotStore(Path.of(env.getRequiredProperty("bridge.store")), mapper);
    }

    @Bean @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
    ObservationService observations(SnapshotStore store, BridgeAccess access, Environment env) {
        return new ObservationService(store, Clock.systemUTC(), access.deviceId,
                env.getProperty("bridge.review-period-seconds", Integer.class, 1800), access.integrationEnabled(), access.observedAmountMinor);
    }

    /** Printed once the process is serving, after every startup refusal has passed. */
    @Bean @ConditionalOnProperty(name = "bridge.enabled", havingValue = "true")
    org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent> modeLine(
            BridgeAccess access, ReceivingQr qr, BridgeLog log) {
        return ready -> log.line(access.modeLine(qr.configured()));
    }

    // Prints any still-suppressed rejection count when the bridge stops.
    @Bean(destroyMethod = "flush") BridgeLog bridgeLog() { return new BridgeLog(System.out, Clock.systemUTC()); }
}

final class BridgeAccess {
    final boolean enabled;
    final PayerTokens payers;
    private final byte[] device, receiver, liveDevice, integration;
    final java.util.UUID deviceId;
    /** RM1.00. A compiled constant: the observed amount can be lowered by configuration, never raised. */
    static final int OBSERVED_AMOUNT_CAP = 100;
    /** Null unless observed-evidence integration mode is configured. */
    final Integer observedAmountMinor;

    BridgeAccess(Environment env) { this(env, new PayerTokens()); }

    BridgeAccess(Environment env, PayerTokens payers) {
        this.payers = java.util.Objects.requireNonNull(payers);
        enabled = env.getProperty("bridge.enabled", Boolean.class, false);
        if (!enabled) { device = new byte[0]; receiver = new byte[0]; liveDevice = new byte[0]; integration = new byte[0]; deviceId = null; observedAmountMinor = null; return; }
        String deviceToken = env.getProperty("bridge.device-token", "");
        String receiverToken = env.getProperty("bridge.receiver-token", "");
        if (!deviceToken.matches("[A-Za-z0-9_-]{32,256}") || !receiverToken.matches("[A-Za-z0-9_-]{32,256}") || deviceToken.equals(receiverToken))
            throw new IllegalStateException("Distinct experimental credentials of 32 to 256 URL-safe characters required");
        try {
            if (!InetAddress.getByName(env.getRequiredProperty("server.address")).isLoopbackAddress())
                throw new IllegalStateException("Experimental backend must bind to loopback");
        } catch (java.net.UnknownHostException ignored) { throw new IllegalStateException("Explicit experimental bind address required"); }
        if (!"NONE".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy", "")))
            throw new IllegalStateException("Forwarded headers must remain disabled");
        device = deviceToken.getBytes(StandardCharsets.UTF_8);
        receiver = receiverToken.getBytes(StandardCharsets.UTF_8);
        String liveToken = env.getProperty("bridge.live-device-token", "");
        String assignedId = env.getProperty("bridge.live-device-id", "");
        if (liveToken.isEmpty() && assignedId.isEmpty()) { liveDevice = new byte[0]; deviceId = null; }
        else {
            if (!liveToken.matches("[A-Za-z0-9_-]{32,256}") || liveToken.equals(deviceToken) || liveToken.equals(receiverToken)
                    || !assignedId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalStateException("Distinct live device credential and UUID required");
            liveDevice = liveToken.getBytes(StandardCharsets.UTF_8);
            deviceId = java.util.UUID.fromString(assignedId);
        }
        // Optional fourth credential: create-by-reference and read-own-reference only. Configuring it makes the
        // process synthetic-only, so no payable QR may be configured alongside it.
        String integrationToken = env.getProperty("bridge.integration-token", "");
        if (integrationToken.isEmpty()) integration = new byte[0];
        else {
            if (!integrationToken.matches("[A-Za-z0-9_-]{32,256}") || integrationToken.equals(deviceToken)
                    || integrationToken.equals(receiverToken) || integrationToken.equals(liveToken))
                throw new IllegalStateException("Distinct integration credential of 32 to 256 URL-safe characters required");
            if (!env.getProperty("bridge.qr-directory", "").isEmpty())
                throw new IllegalStateException("Integration mode is synthetic-only; a receiving QR must not be configured");
            integration = integrationToken.getBytes(StandardCharsets.UTF_8);
        }
        // Observed-evidence integration mode: started only by this owner-launched setting, never by an API caller.
        // The cap is compiled in; no configuration can raise it.
        String observed = env.getProperty("bridge.integration.observed-amount-minor", "");
        if (observed.isEmpty()) observedAmountMinor = null;
        else {
            if (!observed.matches("[1-9][0-9]{0,2}") || Integer.parseInt(observed) > OBSERVED_AMOUNT_CAP)
                throw new IllegalStateException("Observed amount must be a whole number of sen from 1 to " + OBSERVED_AMOUNT_CAP);
            if (integration.length == 0) throw new IllegalStateException("Observed mode requires the integration credential");
            if (deviceId == null) throw new IllegalStateException("Observed mode requires the live device credential and UUID");
            observedAmountMinor = Integer.parseInt(observed);
        }
    }

    boolean integrationEnabled() { return integration.length > 0; }
    boolean observedMode() { return observedAmountMinor != null; }

    /** One text-free line from the effective configuration; it never carries a credential, path or identifier. */
    String modeLine(boolean qrConfigured) {
        return "BRIDGE_MODE mode=" + (observedMode() ? "OBSERVED" : integrationEnabled() ? "SYNTHETIC" : "NON_INTEGRATION")
                + " amount=" + (observedMode() ? observedAmountMinor.toString() : "none") + " qr=" + (qrConfigured ? "configured" : "none")
                + " live=" + (deviceId != null) + " integration=" + integrationEnabled();
    }

    boolean authorized(String header, boolean deviceRole) {
        return authorized(header, deviceRole ? "simulation" : "receiver");
    }
    boolean authorized(String header, String role) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 263) return false;
        byte[] expected = switch (role) {
            case "live" -> liveDevice; case "simulation" -> device; case "integration" -> integration; default -> receiver;
        };
        return expected.length > 0 && MessageDigest.isEqual(expected, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }
}
