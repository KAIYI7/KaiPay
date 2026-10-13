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
    ObservationService observations(SnapshotStore store) { return new ObservationService(store, Clock.systemUTC()); }
}

final class BridgeAccess {
    final boolean enabled;
    private final byte[] device, receiver;

    BridgeAccess(Environment env) {
        enabled = env.getProperty("bridge.enabled", Boolean.class, false);
        if (!enabled) { device = new byte[0]; receiver = new byte[0]; return; }
        String deviceToken = env.getProperty("bridge.device-token", "");
        String receiverToken = env.getProperty("bridge.receiver-token", "");
        if (!deviceToken.matches("[A-Za-z0-9_-]{32,256}") || !receiverToken.matches("[A-Za-z0-9_-]{32,256}") || deviceToken.equals(receiverToken))
            throw new IllegalStateException("Distinct experimental credentials of 32 to 256 URL-safe characters required");
        try {
            if (!InetAddress.getByName(env.getRequiredProperty("server.address")).isLoopbackAddress()
                    && !env.getProperty("server.ssl.enabled", Boolean.class, false))
                throw new IllegalStateException("TLS required outside loopback");
        } catch (java.net.UnknownHostException ignored) { throw new IllegalStateException("Explicit experimental bind address required"); }
        if (!"NONE".equalsIgnoreCase(env.getProperty("server.forward-headers-strategy", "")))
            throw new IllegalStateException("Forwarded headers must remain disabled");
        device = deviceToken.getBytes(StandardCharsets.UTF_8);
        receiver = receiverToken.getBytes(StandardCharsets.UTF_8);
    }

    boolean authorized(String header, boolean deviceRole) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 263) return false;
        return MessageDigest.isEqual(deviceRole ? device : receiver, header.substring(7).getBytes(StandardCharsets.UTF_8));
    }
}
