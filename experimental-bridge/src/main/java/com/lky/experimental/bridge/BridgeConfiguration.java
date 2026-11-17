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
    @Bean FilterRegistrationBean<BridgeFilter> bridgeFilter(BridgeAccess access) {
        FilterRegistrationBean<BridgeFilter> registration = new FilterRegistrationBean<>(new BridgeFilter(access));
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
                env.getProperty("bridge.review-period-seconds", Integer.class, 1800), access.integrationEnabled());
    }
}

final class BridgeAccess {
    final boolean enabled;
    final PayerTokens payers;
    private final byte[] device, receiver, liveDevice, integration;
    final java.util.UUID deviceId;

    BridgeAccess(Environment env) { this(env, new PayerTokens()); }

    BridgeAccess(Environment env, PayerTokens payers) {
        this.payers = java.util.Objects.requireNonNull(payers);
        enabled = env.getProperty("bridge.enabled", Boolean.class, false);
        if (!enabled) { device = new byte[0]; receiver = new byte[0]; liveDevice = new byte[0]; integration = new byte[0]; deviceId = null; return; }
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
    }

    boolean integrationEnabled() { return integration.length > 0; }

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
