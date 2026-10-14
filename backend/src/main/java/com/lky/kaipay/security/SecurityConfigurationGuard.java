package com.lky.kaipay.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.convert.DurationStyle;

/** Fail closed on browser-session and unreviewed proxy configuration. */
@Component
public class SecurityConfigurationGuard implements InitializingBean {
    private final Environment environment;
    public SecurityConfigurationGuard(Environment environment) { this.environment = environment; }
    @Override public void afterPropertiesSet() {
        long formBytes = DataSize.parse(environment.getProperty("server.tomcat.max-http-form-post-size", "8KB")).toBytes();
        if (formBytes <= 0 || formBytes > 8192) {
            throw new IllegalStateException("Form parameter parsing requires a positive limit of at most 8KB");
        }
        if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none"))) {
            throw new IllegalStateException("Forwarded headers require a separately reviewed trusted proxy boundary");
        }
        // Boot can enable RemoteIpValve from either header even when the strategy is NONE.
        for (String header : new String[]{"remote-ip-header", "protocol-header"}) {
            if (!environment.getProperty("server.tomcat.remoteip." + header, "").isBlank()) {
                throw new IllegalStateException("Native forwarded headers require a separately reviewed trusted proxy boundary");
            }
        }
        boolean local = environment.acceptsProfiles(Profiles.of("local"));
        if (local && environment.acceptsProfiles(Profiles.of("prod", "production", "public", "public-demo"))) {
            throw new IllegalStateException("Local HTTP cannot be combined with deployment profiles");
        }
        if (local && !Set.of("127.0.0.1", "::1", "localhost").contains(environment.getProperty("server.address", ""))) {
            throw new IllegalStateException("Local HTTP profile requires a loopback bind address");
        }
        if (!local && !Boolean.parseBoolean(environment.getProperty("server.servlet.session.cookie.secure", "true"))) {
            throw new IllegalStateException("Secure session cookies are required outside the local profile");
        }
        if (!Boolean.parseBoolean(environment.getProperty("server.servlet.session.cookie.http-only", "true"))) {
            throw new IllegalStateException("HttpOnly session cookies are required");
        }
        if (!Set.of("strict", "lax").contains(environment.getProperty("server.servlet.session.cookie.same-site", "strict").toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("Session cookies require SameSite strict or lax");
        }
        if (!"cookie".equalsIgnoreCase(environment.getProperty("server.servlet.session.tracking-modes", "cookie"))) {
            throw new IllegalStateException("Only cookie session tracking is allowed");
        }
        if (!environment.getProperty("server.servlet.session.cookie.domain", "").isBlank()) {
            throw new IllegalStateException("Session cookies must be host-only");
        }
        if (Boolean.parseBoolean(environment.getProperty("server.servlet.session.persistent", "false"))) {
            throw new IllegalStateException("Demo sessions must remain in memory only");
        }
        long timeoutSeconds = DurationStyle.detectAndParse(environment.getProperty("server.servlet.session.timeout", "20m")).toSeconds();
        // Tomcat exposes the idle timeout as signed integer seconds; overflow can disable expiry.
        if (timeoutSeconds < 60 || timeoutSeconds > Integer.MAX_VALUE) {
            throw new IllegalStateException("Session idle timeout must fit positive container seconds and be at least one minute");
        }
        for (String origin : environment.getProperty("kaipay.security.allowed-origins", "").split(",")) {
            if (origin.isBlank()) continue;
            URI uri = URI.create(origin);
            if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())
                    || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) {
                throw new IllegalStateException("CORS requires exact HTTPS origins outside local development");
            }
        }
    }
}
